package com.amsha.core.security.http;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.llm.api.NetworkProfilePolicy;
import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.security.api.OutboundPolicyDecision;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.api.SecretVault;

import org.jboss.logging.Logger;

/**
 * The single governed outbound HTTP transport for all onboarding/provider/MCP/OAuth work.
 *
 * <p>Every call sequence is:
 * <ol>
 *   <li>Validate the target against {@link OutboundHttpPolicyService} (TLS, SSRF, proxy, host
 *       allow-list, HITL). HITL-required results are surfaced as {@link OutboundHttpException.Category#HITL_REQUIRED}
 *       so the caller can route to the approval lifecycle.</li>
 *   <li>Build a per-connection {@link HttpClient} honoring the connection's {@link NetworkProfilePolicy}:
 *       bounded connect/read timeouts, optional proxy (bypassed only for no-proxy hosts), and a TLS
 *       trust mode of {@code SYSTEM} or an explicit custom-CA vault reference (verification bypass is
 *       never available).</li>
 *   <li>Revalidate DNS immediately before connecting: resolve the host and reject any resolved
 *       address that is loopback/link-local/site-local/metadata, preventing DNS rebinding.</li>
 *   <li>Never auto-follow redirects. On a 3xx the transport validates the next hop through the same
 *       policy + DNS revalidation and follows it explicitly, up to a bounded hop count.</li>
 * </ol>
 *
 * <p>The transport is short-lived: a caller builds one per conceptual operation, optionally with a
 * resolved custom-CA / proxy-credential view, then discards it. It must not retain decrypted secret
 * bytes, {@link VaultKey}, or provider responses.</p>
 */
public final class GovernedHttpClient {

    private static final Logger log = Logger.getLogger(GovernedHttpClient.class);

    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
    public static final int DEFAULT_READ_TIMEOUT_MS = 30_000;
    public static final int MAX_REDIRECT_HOPS = 5;

    private final OutboundHttpPolicyService policyService;
    private final SecretVault vault;
    private final OutboundPurpose purpose;
    private final LlmConnectionDto connection;
    private final String actorUserId;
    private final boolean preApproved;
    private final UnaryOperator<URI> policyUriMapper;
    private final Duration connectTimeoutOverride;
    private final InetAddress pinnedAddress;
    private final HttpClient httpClientOverride;

    private GovernedHttpClient(
            OutboundHttpPolicyService policyService,
            SecretVault vault,
            OutboundPurpose purpose,
            LlmConnectionDto connection,
            String actorUserId,
            boolean preApproved,
            UnaryOperator<URI> policyUriMapper,
            Duration connectTimeoutOverride,
            InetAddress pinnedAddress,
            HttpClient httpClientOverride) {
        this.policyService = policyService;
        this.vault = vault;
        this.purpose = purpose;
        this.connection = connection;
        this.actorUserId = actorUserId;
        this.preApproved = preApproved;
        this.policyUriMapper = policyUriMapper;
        this.connectTimeoutOverride = connectTimeoutOverride;
        this.pinnedAddress = pinnedAddress;
        this.httpClientOverride = httpClientOverride;
    }

    public static Builder builder(OutboundHttpPolicyService policyService) {
        return new Builder(policyService);
    }

    /** Validates policy and executes the request, following redirects with per-hop validation. */
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler) {
        return send(request, bodyHandler, 0);
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler, int depth) {
        request = withDefaultTimeout(request);
        URI policyUri = policyUri(request.uri());
        OutboundPolicyDecision decision =
                policyService.evaluate(policyUri.toString(), purpose, connection, actorUserId, preApproved);
        if (decision.isDenied()) {
            log.warnf(
                    "Outbound request denied: %s (reason: %s)",
                    policyUri, decision.reason().orElse("policy"));
            throw OutboundHttpException.policy(
                    "Outbound request denied: " + decision.reason().orElse("policy"));
        }
        if (decision.requiresHitl()) {
            log.infof("Outbound request to %s requires HITL approval", policyUri);
            throw OutboundHttpException.hitl(decision.reason().orElse("approval required"));
        }

        revalidateDns(request.uri());

        List<Runnable> cleanups = new ArrayList<>();
        HttpResponse<T> response;
        try {
            HttpClient client = buildClient(request.uri(), cleanups);
            response = client.send(request, bodyHandler);
        } catch (HttpTimeoutException e) {
            log.warnf("Request timed out: %s", policyUri.getHost());
            throw OutboundHttpException.timeout("Request timed out: " + policyUri.getHost());
        } catch (IOException e) {
            log.warnf("Transport I/O failure to %s", policyUri.getHost());
            throw OutboundHttpException.io("Transport I/O failure to " + policyUri.getHost(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warnf("Interrupted during request to %s", policyUri.getHost());
            throw OutboundHttpException.io("Interrupted during request to " + policyUri.getHost(), e);
        } finally {
            for (Runnable cleanup : cleanups) {
                try {
                    cleanup.run();
                } catch (Exception _) {
                    // Suppress cleanup failures
                }
            }
        }

        int status = response.statusCode();
        if (status >= 300 && status < 400) {
            if (depth >= MAX_REDIRECT_HOPS) {
                throw OutboundHttpException.redirectDenied("Too many redirects (> " + MAX_REDIRECT_HOPS + ")");
            }
            String location = response.headers().firstValue("location").orElse(null);
            if (location == null) {
                throw OutboundHttpException.redirectDenied("HTTP " + status + " with no Location header");
            }
            URI next = request.uri().resolve(location);
            HttpRequest.Builder nextBuilder = HttpRequest.newBuilder(next)
                    .timeout(request.timeout().orElse(Duration.ofMillis(readTimeoutMillis())))
                    .method(request.method(), request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));
            String[] flat = flatten(request.headers().map(), sameOrigin(request.uri(), next));
            if (flat.length > 0) {
                nextBuilder.headers(flat);
            }
            HttpRequest nextRequest = nextBuilder.build();
            return send(nextRequest, bodyHandler, depth + 1);
        }
        return response;
    }

    private static String[] flatten(Map<String, List<String>> headers, boolean sameOrigin) {
        List<String> pairs = new ArrayList<>();
        headers.forEach((k, vs) -> {
            if (!sameOrigin && isSensitiveHeader(k)) return;
            vs.forEach(v -> {
                pairs.add(k);
                pairs.add(v);
            });
        });
        return pairs.toArray(new String[0]);
    }

    private static boolean isSensitiveHeader(String name) {
        return "authorization".equalsIgnoreCase(name)
                || "cookie".equalsIgnoreCase(name)
                || "cookie2".equalsIgnoreCase(name)
                || "proxy-authorization".equalsIgnoreCase(name)
                || "mcp-session-id".equalsIgnoreCase(name)
                || "x-subscription-token".equalsIgnoreCase(name)
                || "x-api-key".equalsIgnoreCase(name)
                || "x-goog-api-key".equalsIgnoreCase(name);
    }

    private static boolean sameOrigin(URI first, URI second) {
        return first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /**
     * Revalidates the resolved destination address immediately before connect against SSRF/rebinding
     * policies and against any configured pinnedAddress. Note: In the absence of an egress proxy that
     * terminates and pins transport resolution, direct remote connections through JDK HttpClient resolve
     * via system DNS; socket-level connection pinning is enforced for proxies and deferred for direct
     * egress as documented in ADR-Onboarding-Multi-Agent-Provider-Model. Sensitive targets subject to
     * DNS rebinding attacks must configure {@code requireProxy=true} on their network profile.
     */
    private void revalidateDns(URI uri) {
        String host = uri.getHost();
        if (host == null || isLocalhost(host)) return;
        String normalized = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (GovernedHttpDnsPinning.isIpLiteral(normalized)) {
            try {
                InetAddress literalAddr = InetAddress.getByName(normalized);
                GovernedHttpDnsPinning.validateAddressSafety(literalAddr, host);
                if (pinnedAddress != null) {
                    GovernedHttpDnsPinning.validateAddressSafety(pinnedAddress, host);
                    if (!literalAddr.equals(pinnedAddress)) {
                        throw OutboundHttpException.dnsRebinding(
                                "Connected address does not match checked address: " + pinnedAddress);
                    }
                }
            } catch (OutboundHttpException e) {
                throw e;
            } catch (Exception _) {
                throw OutboundHttpException.dns("DNS resolution failed for " + host);
            }
            return;
        }
        GovernedHttpDnsPinning.validateHostDns(host, effectiveConnectTimeoutMillis(), pinnedAddress);
    }

    private int effectiveConnectTimeoutMillis() {
        NetworkProfilePolicy policy = connection != null && connection.networkProfileJson() != null
                ? NetworkProfilePolicy.fromJson(connection.networkProfileJson())
                : NetworkProfilePolicy.allowAll();
        return policy.connectTimeoutMs() > 0 ? policy.connectTimeoutMs() : DEFAULT_CONNECT_TIMEOUT_MS;
    }

    private HttpClient buildClient(URI uri, List<Runnable> cleanups) {
        NetworkProfilePolicy policy = connection != null && connection.networkProfileJson() != null
                ? NetworkProfilePolicy.fromJson(connection.networkProfileJson())
                : NetworkProfilePolicy.allowAll();

        if (policy.requireProxy() && !policy.isNoProxyHost(uri.getHost())) {
            if (httpClientOverride != null) {
                if (httpClientOverride.proxy().isEmpty()) {
                    throw OutboundHttpException.policy("Proxy transport required by connection policy");
                }
                if (policy.proxyCredentialVaultRef().isPresent()) {
                    String ref = policy.proxyCredentialVaultRef().get();
                    if (vault == null) {
                        throw OutboundHttpException.policy(
                                "Proxy credentials are configured but no vault is available");
                    }
                    if (vault.get(ref, actorUserId).isEmpty()) {
                        throw OutboundHttpException.policy("Proxy credential vault reference could not be resolved");
                    }
                }
                return httpClientOverride;
            }
        }

        if (policy.tlsTrustMode() == NetworkProfilePolicy.TlsTrustMode.CUSTOM_CA) {
            String caRef = policy.customCaVaultRef().orElse(null);
            if (caRef == null || caRef.isBlank()) {
                throw OutboundHttpException.tls("Custom-CA trust mode requires a customCaVaultRef");
            }
            if (vault == null) {
                throw OutboundHttpException.tls("Custom-CA trust requires a vault");
            }
            if (vault.get(caRef, actorUserId).isEmpty()) {
                throw OutboundHttpException.tls("Custom-CA vault reference could not be resolved");
            }
            if (httpClientOverride != null) {
                return httpClientOverride;
            }
        }

        if (httpClientOverride != null) {
            return httpClientOverride;
        }

        HttpClient.Builder builder = HttpClient.newBuilder();
        Duration connectTimeout = connectTimeoutOverride != null
                ? connectTimeoutOverride
                : Duration.ofMillis(effectiveConnectTimeoutMillis());
        builder.connectTimeout(connectTimeout);
        builder.followRedirects(HttpClient.Redirect.NEVER);

        if (policy.requireProxy() && !policy.isNoProxyHost(uri.getHost())) {
            String proxyUrl = policy.proxyUrl().orElse(null);
            GovernedHttpDnsPinning.PinnedProxy pinnedProxy =
                    GovernedHttpDnsPinning.validateTrustedProxy(proxyUrl, effectiveConnectTimeoutMillis());
            ProxySelector selector = ProxySelector.of(pinnedProxy.socketAddress());
            builder.proxy(selector);
            policy.proxyCredentialVaultRef()
                    .ifPresent(ref -> GovernedHttpSecuritySupport.applyProxyCredentials(
                            builder, vault, actorUserId, ref, cleanups));
        }

        if (policy.tlsTrustMode() == NetworkProfilePolicy.TlsTrustMode.CUSTOM_CA) {
            String caRef = policy.customCaVaultRef().orElse(null);
            builder.sslContext(GovernedHttpSecuritySupport.buildCustomCaContext(vault, actorUserId, caRef));
        }

        return builder.build();
    }

    private int readTimeoutMillis() {
        if (connection == null || connection.networkProfileJson() == null) return DEFAULT_READ_TIMEOUT_MS;
        int configured =
                NetworkProfilePolicy.fromJson(connection.networkProfileJson()).readTimeoutMs();
        return configured > 0 ? configured : DEFAULT_READ_TIMEOUT_MS;
    }

    private HttpRequest withDefaultTimeout(HttpRequest request) {
        if (request.timeout().isPresent()) return request;
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                .timeout(Duration.ofMillis(readTimeoutMillis()))
                .method(request.method(), request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));
        String[] headers = flatten(request.headers().map(), true);
        if (headers.length > 0) builder.headers(headers);
        return builder.build();
    }

    public static boolean isLocalhost(String host) {
        if (host == null) return false;
        String normalized = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        return normalized.equals("localhost") || normalized.equals("127.0.0.1") || normalized.equals("::1");
    }

    public static boolean isIpv4MappedAddress(byte[] bytes) {
        return GovernedHttpDnsPinning.isIpv4MappedAddress(bytes);
    }

    private URI policyUri(URI requestUri) {
        URI mapped = policyUriMapper.apply(requestUri);
        if (mapped == null || mapped.getScheme() == null || mapped.getHost() == null) {
            throw OutboundHttpException.policy("Outbound policy URI mapping produced an invalid target");
        }
        if (!sameOrigin(requestUri, mapped)) {
            throw OutboundHttpException.policy("Outbound policy URI mapping changed the request origin");
        }
        return mapped;
    }

    public static final class Builder {
        private final OutboundHttpPolicyService policyService;
        private SecretVault vault;
        private OutboundPurpose purpose = OutboundPurpose.PROVIDER_API_CALL;
        private LlmConnectionDto connection;
        private String actorUserId;
        private boolean preApproved;
        private UnaryOperator<URI> policyUriMapper = UnaryOperator.identity();
        private Duration connectTimeoutOverride;
        private InetAddress pinnedAddress;
        private HttpClient httpClientOverride;

        private Builder(OutboundHttpPolicyService policyService) {
            this.policyService = policyService;
        }

        public Builder vault(SecretVault vault) {
            this.vault = vault;
            return this;
        }

        public Builder purpose(OutboundPurpose purpose) {
            this.purpose = purpose;
            return this;
        }

        public Builder connection(LlmConnectionDto connection) {
            this.connection = connection;
            return this;
        }

        public Builder actor(String actorUserId) {
            this.actorUserId = actorUserId;
            return this;
        }

        /** Marks the operation as already HITL-approved (e.g. committed via the onboarding saga). */
        public Builder preApproved(boolean preApproved) {
            this.preApproved = preApproved;
            return this;
        }

        /**
         * Supplies the URI used for policy evaluation and diagnostics while leaving the actual
         * request URI unchanged. This is intended for requests whose path contains a credential.
         */
        public Builder policyUriMapper(UnaryOperator<URI> policyUriMapper) {
            this.policyUriMapper = Objects.requireNonNull(policyUriMapper, "policyUriMapper must not be null");
            return this;
        }

        /** Overrides the connection-derived connect timeout for a bounded transport caller. */
        public Builder connectTimeout(Duration connectTimeout) {
            if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
                throw new IllegalArgumentException("connectTimeout must be positive");
            }
            this.connectTimeoutOverride = connectTimeout;
            return this;
        }

        /**
         * Sets the expected validated destination address for pre-connect safety checks and address matching.
         * For direct connections, Java HttpClient resolves DNS via system resolver; strict socket-level pinning
         * requires an egress proxy with {@code requireProxy=true}.
         */
        public Builder pinnedAddress(InetAddress pinnedAddress) {
            this.pinnedAddress = pinnedAddress;
            return this;
        }

        /**
         * Installs a caller-supplied {@link HttpClient} (test seam).
         *
         * <p>The override must not follow redirects. {@link #send} depends on seeing the 3xx response
         * itself so it can re-run policy evaluation and DNS revalidation on the next hop; a client that
         * follows redirects internally would skip both, turning a governed transport into an ungoverned
         * one. A supplied {@link HttpClient} cannot be reconfigured after construction, so an unsafe one
         * is rejected rather than repaired.
         */
        public Builder httpClient(HttpClient httpClient) {
            Objects.requireNonNull(httpClient, "httpClient must not be null");
            HttpClient.Redirect redirectPolicy = httpClient.followRedirects();
            if (redirectPolicy != HttpClient.Redirect.NEVER) {
                throw new IllegalArgumentException("httpClient override must not follow redirects, but was "
                        + redirectPolicy
                        + ": per-hop policy evaluation and DNS revalidation depend on observing the redirect");
            }
            this.httpClientOverride = httpClient;
            return this;
        }

        public GovernedHttpClient build() {
            return new GovernedHttpClient(
                    policyService,
                    vault,
                    purpose,
                    connection,
                    actorUserId,
                    preApproved,
                    policyUriMapper,
                    connectTimeoutOverride,
                    pinnedAddress,
                    httpClientOverride);
        }
    }
}
