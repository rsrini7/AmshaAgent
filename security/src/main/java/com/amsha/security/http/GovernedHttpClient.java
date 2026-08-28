package com.amsha.security.http;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.common.util.LogUtil;
import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.llm.api.NetworkProfilePolicy;
import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.security.api.OutboundPolicyDecision;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultKey;

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

    /** Default connect timeout when the policy omits one. */
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
    /** Default total request timeout when the policy omits one. */
    public static final int DEFAULT_READ_TIMEOUT_MS = 30_000;
    /** Maximum number of explicitly-followed redirect hops. */
    public static final int MAX_REDIRECT_HOPS = 5;

    private final OutboundHttpPolicyService policyService;
    private final SecretVault vault;
    private final OutboundPurpose purpose;
    private final LlmConnectionDto connection;
    private final String actorUserId;
    private final boolean preApproved;

    private GovernedHttpClient(
            OutboundHttpPolicyService policyService,
            SecretVault vault,
            OutboundPurpose purpose,
            LlmConnectionDto connection,
            String actorUserId,
            boolean preApproved) {
        this.policyService = policyService;
        this.vault = vault;
        this.purpose = purpose;
        this.connection = connection;
        this.actorUserId = actorUserId;
        this.preApproved = preApproved;
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
        OutboundPolicyDecision decision =
                policyService.evaluate(request.uri().toString(), purpose, connection, actorUserId, preApproved);
        if (decision.isDenied()) {
            log.warnf(
                    "Outbound request denied: %s (reason: %s)",
                    request.uri(), decision.reason().orElse("policy"));
            throw OutboundHttpException.policy(
                    "Outbound request denied: " + decision.reason().orElse("policy"));
        }
        if (decision.requiresHitl()) {
            log.infof("Outbound request to %s requires HITL approval", request.uri());
            throw OutboundHttpException.hitl(decision.reason().orElse("approval required"));
        }

        revalidateDns(request.uri());

        HttpClient client = buildClient(request.uri());
        HttpResponse<T> response;
        try {
            response = client.send(request, bodyHandler);
        } catch (HttpTimeoutException e) {
            log.warnf("Request timed out: %s (%s)", request.uri().getHost(), LogUtil.rootMessage(e));
            throw OutboundHttpException.timeout(
                    "Request timed out: " + request.uri().getHost());
        } catch (IOException e) {
            log.warnf("Transport I/O failure to %s (%s)", request.uri().getHost(), LogUtil.rootMessage(e));
            throw OutboundHttpException.io(
                    "Transport I/O failure to " + request.uri().getHost(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warnf("Interrupted during request to %s (%s)", request.uri().getHost(), LogUtil.rootMessage(e));
            throw OutboundHttpException.io(
                    "Interrupted during request to " + request.uri().getHost(), e);
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
                || "mcp-session-id".equalsIgnoreCase(name);
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

    /** Revalidates the resolved address immediately before connect to defeat DNS rebinding. */
    private void revalidateDns(URI uri) {
        String host = uri.getHost();
        if (host == null) return;
        // Skip literal localhost (development/Ollama/MCP) — already exempted by the policy service.
        if (isLocalhost(host)) return;
        String normalized = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        // For literal IPs the policy service already blocked private ranges; only DNS names need revalidation.
        if (normalized.matches("[0-9a-fA-F:.]+")) return;
        try {
            InetAddress[] addresses = InetAddress.getAllByName(normalized);
            for (InetAddress addr : addresses) {
                if (addr.isLoopbackAddress()
                        || addr.isLinkLocalAddress()
                        || addr.isSiteLocalAddress()
                        || addr.isMulticastAddress()
                        || addr.isAnyLocalAddress()) {
                    throw OutboundHttpException.dnsRebinding(
                            "Resolved address for " + host + " is a prohibited internal/loopback range");
                }
                byte[] bytes = addr.getAddress();
                if (bytes.length == 4
                        && (bytes[0] & 0xff) == 169
                        && (bytes[1] & 0xff) == 254
                        && (bytes[2] & 0xff) == 169
                        && (bytes[3] & 0xff) == 254) {
                    // Explicit pin for the cloud metadata endpoint. isLinkLocalAddress() already covers
                    // 169.254.0.0/16; this makes the highest-value target unmissable even if the
                    // link-local check is ever narrowed.
                    throw OutboundHttpException.dnsRebinding(
                            "Resolved address for " + host + " is the cloud metadata endpoint");
                }
                if (bytes.length == 4
                        && (bytes[0] & 0xff) == 100
                        && ((bytes[1] & 0xff) >= 64 && (bytes[1] & 0xff) <= 127)) {
                    throw OutboundHttpException.dnsRebinding(
                            "Resolved address for " + host + " is in shared address space (100.64.0.0/10)");
                }
                if (isIpv4MappedAddress(bytes)) {
                    throw OutboundHttpException.dnsRebinding(
                            "Resolved address for " + host + " is an IPv4-mapped address");
                }
                if (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc) {
                    throw OutboundHttpException.dnsRebinding(
                            "Resolved address for " + host + " is an IPv6 Unique Local Address (fc00::/7)");
                }
            }
        } catch (OutboundHttpException e) {
            throw e;
        } catch (Exception _) {
            throw OutboundHttpException.dns("DNS resolution failed for " + host);
        }
    }

    /** Builds a per-connection HttpClient honoring the network profile policy. */
    private static boolean isIpv4MappedAddress(byte[] bytes) {
        if (bytes.length != 16) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }

    private HttpClient buildClient(URI uri) {
        NetworkProfilePolicy policy = connection != null && connection.networkProfileJson() != null
                ? NetworkProfilePolicy.fromJson(connection.networkProfileJson())
                : NetworkProfilePolicy.allowAll();

        HttpClient.Builder builder = HttpClient.newBuilder();

        int connectMs = policy.connectTimeoutMs() > 0 ? policy.connectTimeoutMs() : DEFAULT_CONNECT_TIMEOUT_MS;
        builder.connectTimeout(Duration.ofMillis(connectMs));
        builder.followRedirects(HttpClient.Redirect.NEVER);

        // Proxy: required by policy unless the host is a no-proxy exclusion.
        if (policy.requireProxy() && !policy.isNoProxyHost(uri.getHost())) {
            String proxyUrl = policy.proxyUrl().orElse(null);
            if (proxyUrl == null || proxyUrl.isBlank()) {
                throw OutboundHttpException.policy("Connection requires a proxy but none is configured");
            }
            try {
                URI proxyUri = URI.create(proxyUrl);
                int proxyPort = proxyUri.getPort() < 0 ? 80 : proxyUri.getPort();
                ProxySelector selector = ProxySelector.of(new InetSocketAddress(proxyUri.getHost(), proxyPort));
                builder.proxy(selector);
                // Authenticated proxy: resolve the opaque vault reference to bytes only for this build.
                policy.proxyCredentialVaultRef().ifPresent(ref -> applyProxyCredentials(builder, ref));
            } catch (OutboundHttpException e) {
                throw e;
            } catch (Exception _) {
                throw OutboundHttpException.policy("Invalid proxy URL: " + proxyUrl);
            }
        }

        // TLS trust mode.
        if (policy.tlsTrustMode() == NetworkProfilePolicy.TlsTrustMode.CUSTOM_CA) {
            String caRef = policy.customCaVaultRef().orElse(null);
            if (caRef == null || caRef.isBlank()) {
                throw OutboundHttpException.tls("Custom-CA trust mode requires a customCaVaultRef");
            }
            builder.sslContext(buildCustomCaContext(caRef));
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

    private void applyProxyCredentials(HttpClient.Builder builder, String ref) {
        if (vault == null) {
            throw OutboundHttpException.policy("Proxy credentials are configured but no vault is available");
        }
        Optional<VaultKey> key = vault.get(ref, actorUserId);
        if (key.isEmpty()) {
            throw OutboundHttpException.policy("Proxy credential vault reference could not be resolved");
        }
        try (VaultKey k = key.get()) {
            String cred = new String(k.get(), StandardCharsets.UTF_8);
            String[] parts = cred.split(":", 2);
            if (parts.length != 2) {
                throw OutboundHttpException.policy("Proxy credential must be user:password");
            }
            Authenticator auth = new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(parts[0], parts[1].toCharArray());
                }
            };
            builder.authenticator(auth);
        } catch (OutboundHttpException e) {
            throw e;
        } catch (Exception _) {
            throw OutboundHttpException.policy("Failed to resolve proxy credentials");
        }
    }

    private SSLContext buildCustomCaContext(String caRef) {
        if (vault == null) {
            throw OutboundHttpException.tls("Custom-CA trust requires a vault");
        }
        Optional<VaultKey> key = vault.get(caRef, actorUserId);
        if (key.isEmpty()) {
            throw OutboundHttpException.tls("Custom-CA vault reference could not be resolved");
        }
        try (VaultKey k = key.get()) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate ca = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(k.get()));
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null);
            ks.setCertificateEntry("amsha-custom-ca", ca);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), new SecureRandom());
            return ctx;
        } catch (OutboundHttpException e) {
            throw e;
        } catch (Exception e) {
            throw OutboundHttpException.tls("Failed to establish custom-CA trust: " + e.getMessage());
        }
    }

    private static boolean isLocalhost(String host) {
        if (host == null) return false;
        String normalized = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        return normalized.equals("localhost") || normalized.equals("127.0.0.1") || normalized.equals("::1");
    }

    /** Builder for a short-lived governed client. */
    public static final class Builder {
        private final OutboundHttpPolicyService policyService;
        private SecretVault vault;
        private OutboundPurpose purpose = OutboundPurpose.PROVIDER_API_CALL;
        private LlmConnectionDto connection;
        private String actorUserId;
        private boolean preApproved;

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

        public GovernedHttpClient build() {
            return new GovernedHttpClient(policyService, vault, purpose, connection, actorUserId, preApproved);
        }
    }
}
