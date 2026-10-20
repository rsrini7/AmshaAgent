package com.amsha.core.classifier.remote;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;
import com.amsha.classifier.api.DecisionEngine;
import com.amsha.classifier.api.DecisionRequest;
import com.amsha.classifier.api.DecisionResult;
import com.amsha.classifier.api.DecisionTransactionGuard;
import com.amsha.classifier.api.ProviderCategory;
import com.amsha.classifier.api.RedactionPolicy;
import com.amsha.classifier.api.RemoteDecisionProvider;
import com.amsha.classifier.api.StateProjectionPolicy;
import com.amsha.common.http.HttpProtocol;
import com.amsha.core.classifier.selfhosted.StateProjectionRedactor;
import com.amsha.core.classifier.wire.SystemOneWireMapper;
import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.core.security.http.GovernedHttpClient;
import com.amsha.core.security.http.OutboundHttpException;
import com.amsha.security.api.OutboundPolicyDecision;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.api.SecretVault;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

import io.vertx.core.Context;

/**
 * Separate-process decision engine evaluating against a TypeSafe-compatible remote HTTP service (§3, §7.6).
 * Implements {@link DecisionEngine} and {@link RemoteDecisionProvider}.
 *
 * <p>Security and architecture invariants:
 * <ul>
 *   <li>Category is {@link ProviderCategory#REMOTE}; strictly forbidden from classifier SPI placement.</li>
 *   <li>Governed HTTP Transport: outbound requests MUST route through {@link GovernedHttpClient}
 *       which owns trusted-proxy validation, proxy credentials, custom-CA loading, and DNS safety checks.</li>
 *   <li>No Retries: remote decision evaluation is strictly single-shot (no transport-level retries). Any
 *       transient error, timeout, or overload (429/503) returns a deterministic {@code NoOpinion} immediately,
 *       allowing the coordinator to govern its own turn budget and fallback behavior without latency multiplication.</li>
 *   <li>Proxy and TLS enforcement: honors {@code NetworkProfilePolicy} via {@link GovernedHttpClient}.</li>
 *   <li>Non-blocking thread guard: evaluation must run on worker/virtual threads, failing fast on Vert.x event loops.</li>
 *   <li>Defensive transaction guard: evaluation fails closed if called inside active JTA transactions (§5.9).</li>
 *   <li>Outbound policy & HITL: a remote decision cannot self-authorize its own outbound network call.</li>
 *   <li>Allow-listed state projection: drops all unapproved fields before serialization; missing policy fails closed.</li>
 *   <li>Payload-free diagnostics: no sensitive input excerpts, authorization headers, or provider bodies leak in errors.</li>
 *   <li>Safe lifecycle: shutdown drains in-flight requests and rejects new evaluations with NoOpinion(DISABLED).</li>
 * </ul>
 */
public final class RemoteDecisionEngine implements DecisionEngine, RemoteDecisionProvider, AutoCloseable {

    private static final Logger log = Logger.getLogger(RemoteDecisionEngine.class);
    private static final Duration SHUTDOWN_DRAIN_TIMEOUT = Duration.ofSeconds(2);

    private final RemoteProviderConfig config;
    private final ObjectMapper mapper;
    private final StateProjectionRedactor redactor;
    private final DecisionTransactionGuard transactionGuard;
    private final OutboundHttpPolicyService outboundPolicyService;
    private final SecretVault vault;
    private final HttpClient testHttpClient;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger inFlightRequests = new AtomicInteger(0);
    private final Object drainLock = new Object();

    public RemoteDecisionEngine(
            RemoteProviderConfig config,
            ObjectMapper mapper,
            DecisionTransactionGuard transactionGuard,
            OutboundHttpPolicyService outboundPolicyService,
            SecretVault vault,
            HttpClient testHttpClient) {
        this.config = Objects.requireNonNull(config, "config");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.redactor = new StateProjectionRedactor(mapper);
        this.transactionGuard = transactionGuard != null ? transactionGuard : DecisionTransactionGuard.system();
        this.outboundPolicyService = Objects.requireNonNull(outboundPolicyService, "outboundPolicyService");
        this.vault = vault;
        this.testHttpClient = testHttpClient;
    }

    public RemoteDecisionEngine(
            RemoteProviderConfig config,
            ObjectMapper mapper,
            DecisionTransactionGuard transactionGuard,
            OutboundHttpPolicyService outboundPolicyService,
            SecretVault vault) {
        this(config, mapper, transactionGuard, outboundPolicyService, vault, null);
    }

    public RemoteDecisionEngine(
            RemoteProviderConfig config,
            ObjectMapper mapper,
            DecisionTransactionGuard transactionGuard,
            OutboundHttpPolicyService outboundPolicyService) {
        this(config, mapper, transactionGuard, outboundPolicyService, null, null);
    }

    public RemoteDecisionEngine(
            RemoteProviderConfig config, ObjectMapper mapper, OutboundHttpPolicyService outboundPolicyService) {
        this(config, mapper, DecisionTransactionGuard.system(), outboundPolicyService, null, null);
    }

    public RemoteDecisionEngine(
            RemoteProviderConfig config,
            HttpClient testHttpClient,
            ObjectMapper mapper,
            DecisionTransactionGuard transactionGuard,
            OutboundHttpPolicyService outboundPolicyService,
            SecretVault vault) {
        this(config, mapper, transactionGuard, outboundPolicyService, vault, testHttpClient);
    }

    public RemoteDecisionEngine(
            RemoteProviderConfig config,
            HttpClient testHttpClient,
            ObjectMapper mapper,
            DecisionTransactionGuard transactionGuard,
            OutboundHttpPolicyService outboundPolicyService) {
        this(config, mapper, transactionGuard, outboundPolicyService, null, testHttpClient);
    }

    public RemoteDecisionEngine(
            RemoteProviderConfig config,
            HttpClient testHttpClient,
            ObjectMapper mapper,
            OutboundHttpPolicyService outboundPolicyService) {
        this(config, mapper, DecisionTransactionGuard.system(), outboundPolicyService, null, testHttpClient);
    }

    @Override
    public ProviderCategory category() {
        return ProviderCategory.REMOTE;
    }

    public boolean isEnabled() {
        return config.enabled() && !closed.get();
    }

    public int inFlightRequests() {
        return inFlightRequests.get();
    }

    public RemoteProviderConfig config() {
        return config;
    }

    @Override
    public DecisionResult evaluate(DecisionRequest request) {
        Objects.requireNonNull(request, "request");

        // 1. Non-blocking Event-Loop Guard (§2.3, AGENTS.md)
        if (Context.isOnEventLoopThread()) {
            return new DecisionResult.NoOpinion(
                    request.decisionId(),
                    request.schemaVersion(),
                    NoOpinionReason.OVERLOAD_REJECTED,
                    null,
                    "Remote decision evaluation must not execute on Vert.x event-loop thread");
        }

        // 2. Defensive Transaction Guard (§5.9)
        if (transactionGuard.isInActiveTransaction()) {
            return new DecisionResult.NoOpinion(
                    request.decisionId(),
                    request.schemaVersion(),
                    NoOpinionReason.OVERLOAD_REJECTED,
                    null,
                    "Decision evaluation rejected: active JTA write transaction detected (§5.9)");
        }

        // 3. Disabled/Shutdown Check
        if (!isEnabled()) {
            return new DecisionResult.NoOpinion(
                    request.decisionId(),
                    request.schemaVersion(),
                    NoOpinionReason.DISABLED,
                    null,
                    "Remote decision provider is disabled");
        }

        // 4. Mandatory Outbound HTTP Policy & HITL Self-Authorization Guard
        OutboundPolicyDecision outboundDecision = outboundPolicyService.evaluate(
                config.endpoint().toString(), OutboundPurpose.PROVIDER_API_CALL, config.connection(), "system");
        if (outboundDecision.isDenied()) {
            return DecisionResult.NoOpinion.ofMalformedResponse(
                    request.decisionId(),
                    request.schemaVersion(),
                    null,
                    "Outbound policy denied egress for remote decision provider: "
                            + outboundDecision.reason().orElse("policy"));
        }
        if (outboundDecision.requiresHitl()) {
            return new DecisionResult.NoOpinion(
                    request.decisionId(),
                    request.schemaVersion(),
                    NoOpinionReason.OVERLOAD_REJECTED,
                    null,
                    "Outbound policy requires HITL approval; remote evaluation cannot self-authorize");
        }

        inFlightRequests.incrementAndGet();
        try {
            if (closed.get()) {
                return new DecisionResult.NoOpinion(
                        request.decisionId(),
                        request.schemaVersion(),
                        NoOpinionReason.DISABLED,
                        null,
                        "Remote decision provider is disabled");
            }
            return executeEvaluation(request);
        } finally {
            if (inFlightRequests.decrementAndGet() == 0) {
                synchronized (drainLock) {
                    drainLock.notifyAll();
                }
            }
        }
    }

    private DecisionResult executeEvaluation(DecisionRequest request) {
        // 5. Remote Endpoint & TLS Validation
        try {
            RemoteEndpointValidator.validateRemoteEndpoint(config.endpoint());
        } catch (SecurityException | IllegalArgumentException _) {
            return DecisionResult.NoOpinion.ofMalformedResponse(
                    request.decisionId(), request.schemaVersion(), null, "Remote endpoint validation failed");
        }

        // 6. Explicit Allow-listed State Projection & Redaction (§3, §7.4, §7.6)
        StateProjectionPolicy projectionPolicy = resolveProjectionPolicy(request);
        if (projectionPolicy == null) {
            return DecisionResult.NoOpinion.ofMalformedResponse(
                    request.decisionId(),
                    request.schemaVersion(),
                    null,
                    "Missing allow-listed state projection policy for remote provider");
        }

        String projectedStateJson;
        try {
            projectedStateJson = redactor.projectAndRedact(request.state(), projectionPolicy);
        } catch (Exception _) {
            return DecisionResult.NoOpinion.ofMalformedResponse(
                    request.decisionId(), request.schemaVersion(), null, "State projection or redaction failed");
        }

        // 7. Map onto the System One wire contract. An unrepresentable question fails closed rather
        //    than emitting a request the provider would reject with 422.
        SystemOneWireMapper.Outcome mapped =
                RemoteDecisionWireCodec.buildWireRequestBody(mapper, request, projectedStateJson, config.modelId());

        // 8. Execute Outbound Remote HTTP Call via GovernedHttpClient (single-shot, no retries)
        return switch (mapped) {
            case SystemOneWireMapper.Outcome.Ready ready -> executeHttpCall(request, ready.body());
            case SystemOneWireMapper.Outcome.Unrepresentable unrepresentable ->
                DecisionResult.NoOpinion.ofMalformedResponse(
                        request.decisionId(), request.schemaVersion(), null, unrepresentable.reason());
        };
    }

    private StateProjectionPolicy resolveProjectionPolicy(DecisionRequest request) {
        if (config.defaultProjectionPolicy() != null) {
            Set<String> allowed = config.defaultProjectionPolicy().allowedFields();
            RedactionPolicy configRedaction = config.defaultProjectionPolicy().redactionPolicy();
            RedactionPolicy reqRedaction = request.redactionPolicy();
            RedactionPolicy effectiveRedaction = mergeRedactionPolicies(configRedaction, reqRedaction);
            return StateProjectionPolicy.of(allowed, effectiveRedaction);
        }
        // Fail closed: Never invent allow-list from question IDs or arbitrary fields
        return null;
    }

    private static RedactionPolicy mergeRedactionPolicies(RedactionPolicy p1, RedactionPolicy p2) {
        if (p1 == null) {
            return p2 != null ? p2 : RedactionPolicy.DEFAULT;
        }
        if (p2 == null) {
            return p1;
        }
        boolean redactSecrets = p1.redactSecrets() || p2.redactSecrets();
        boolean redactPii = p1.redactPii() || p2.redactPii();
        Set<String> masked = new HashSet<>(p1.maskedFields());
        masked.addAll(p2.maskedFields());
        return new RedactionPolicy(redactSecrets, redactPii, Set.copyOf(masked));
    }

    private DecisionResult executeHttpCall(DecisionRequest request, String requestBody) {
        Duration effectiveTimeout = calculateTimeout(request.timeout(), config.timeout());
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(config.endpoint())
                .timeout(effectiveTimeout)
                .header(HttpProtocol.HEADER_CONTENT_TYPE, HttpProtocol.MEDIA_TYPE_JSON)
                .header(HttpProtocol.HEADER_ACCEPT, HttpProtocol.MEDIA_TYPE_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));

        config.optionalApiKey()
                .ifPresent(key ->
                        reqBuilder.header(HttpProtocol.HEADER_AUTHORIZATION, HttpProtocol.AUTH_SCHEME_BEARER + key));
        HttpRequest httpRequest = reqBuilder.build();

        GovernedHttpClient.Builder clientBuilder = GovernedHttpClient.builder(outboundPolicyService)
                .purpose(OutboundPurpose.PROVIDER_API_CALL)
                .connection(config.connection())
                .vault(vault)
                .actor("system")
                .connectTimeout(effectiveTimeout)
                .timingListener(RemoteDecisionEngine::logPhaseTimings);

        if (testHttpClient != null) {
            clientBuilder.httpClient(testHttpClient);
        }

        GovernedHttpClient client = clientBuilder.build();
        try {
            // Single-shot execution: strictly no retries
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            return RemoteDecisionWireCodec.handleHttpResponse(mapper, request, response);
        } catch (OutboundHttpException ex) {
            return RemoteDecisionWireCodec.mapOutboundException(request, ex, effectiveTimeout);
        } catch (Exception ex) {
            return DecisionResult.NoOpinion.ofMalformedResponse(
                    request.decisionId(),
                    request.schemaVersion(),
                    null,
                    "Remote transport failure: " + ex.getClass().getSimpleName());
        }
    }

    /**
     * Emits a payload-free per-hop timing breakdown at INFO so an evidence run's log records where the
     * outbound latency was spent. Durations only: no host, URI, credential, or body content.
     *
     * <p>{@code transport} is one combined <b>connection / TLS / remote-exchange</b> bucket: connection
     * setup (TCP + TLS) plus send and receive, because the JDK client exposes no hook to time connect and
     * handshake separately. This engine builds a fresh governed client — and therefore a fresh
     * {@link HttpClient} and connection pool — per call, so every hop pays a cold connect inside that span.
     * <b>Do not infer a TLS share from it.</b> A transport-dominated breakdown means "connect and/or
     * provider"; attributing it needs a controlled connection-resumption experiment, which this
     * instrumentation is meant to justify or rule out.
     */
    private static void logPhaseTimings(GovernedHttpClient.PhaseTimings timings) {
        log.infof(
                "Decision outbound timing: policy=%dms dnsPinning=%dms clientSetup=%dms transport=%dms",
                Math.round(timings.policyNanos() / 1_000_000.0),
                Math.round(timings.dnsPinningNanos() / 1_000_000.0),
                Math.round(timings.clientSetupNanos() / 1_000_000.0),
                Math.round(timings.transportNanos() / 1_000_000.0));
    }

    private static Duration calculateTimeout(Duration reqTimeout, Duration confTimeout) {
        if (reqTimeout == null) {
            return confTimeout;
        }
        return reqTimeout.compareTo(confTimeout) < 0 ? reqTimeout : confTimeout;
    }

    /**
     * Closes this decision engine, draining in-flight requests up to {@link #SHUTDOWN_DRAIN_TIMEOUT}
     * and rejecting subsequent evaluations with {@code NoOpinion(DISABLED)}.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        drainInFlightRequests(SHUTDOWN_DRAIN_TIMEOUT);
    }

    private void drainInFlightRequests(Duration maxWait) {
        long deadline = System.nanoTime() + maxWait.toNanos();
        synchronized (drainLock) {
            while (inFlightRequests.get() > 0) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    break;
                }
                try {
                    long remainingMs = remainingNanos / 1_000_000;
                    if (remainingMs > 0) {
                        drainLock.wait(Math.min(remainingMs, 50));
                    }
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
}
