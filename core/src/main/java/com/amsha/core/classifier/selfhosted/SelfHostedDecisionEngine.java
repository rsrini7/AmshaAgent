package com.amsha.core.classifier.selfhosted;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;
import com.amsha.classifier.api.DecisionEngine;
import com.amsha.classifier.api.DecisionPayloadDecoder;
import com.amsha.classifier.api.DecisionRequest;
import com.amsha.classifier.api.DecisionResult;
import com.amsha.classifier.api.DecisionTransactionGuard;
import com.amsha.classifier.api.ProviderCategory;
import com.amsha.classifier.api.RedactionPolicy;
import com.amsha.classifier.api.SelfHostedDecisionProvider;
import com.amsha.classifier.api.StateProjectionPolicy;
import com.amsha.classifier.api.ToolRiskShadowContext;
import com.amsha.common.http.HttpProtocol;
import com.amsha.core.classifier.DecisionToolRiskShadowClassifier;
import com.amsha.core.classifier.wire.SystemOneWireMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.vertx.core.Context;

/**
 * Separate-process decision engine evaluating against a self-hosted loopback HTTP service (§7.5).
 * State inputs must be non-blank JSON objects; arbitrary text states are not supported.
 * Invoke {@link #evaluate(DecisionRequest)} from a worker or virtual thread, never a Vert.x event-loop thread,
 * because the HTTP exchange uses blocking {@link HttpClient#send(HttpRequest, HttpResponse.BodyHandler)}.
 * Implements {@link DecisionEngine} and {@link SelfHostedDecisionProvider}.
 *
 * <p>Invariant enforcement:
 * <ul>
 *   <li>Category is {@link ProviderCategory#SELF_HOSTED}; forbidden from classifier SPI placement.</li>
 *   <li>Requires strict 127.0.0.1 loopback binding unconditionally; fails closed on wildcard or non-loopback exposure.</li>
 *   <li>Enforces explicit allow-listed state projection and redaction before serialization; excluded fields dropped.</li>
 *   <li>Rejects evaluation on a Vert.x event-loop thread before any blocking work, testing the actual executing thread.</li>
 *   <li>Defensive transaction guard prevents external HTTP calls inside active JTA transactions.</li>
 *   <li>Missing model, timeout, overload, and provider failure return deterministic fallback NoOpinion.</li>
 *   <li>Shutdown drains in-flight requests and rejects new evaluations with NoOpinion(DISABLED); injected client lifecycle remains with its creator.</li>
 * </ul>
 */
public final class SelfHostedDecisionEngine implements DecisionEngine, SelfHostedDecisionProvider, AutoCloseable {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Duration SHUTDOWN_DRAIN_TIMEOUT = Duration.ofSeconds(2);
    private static final String LOOPBACK_TOKEN_HEADER = "X-Amsha-Loopback-Token";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final SelfHostedProviderConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final StateProjectionRedactor redactor;
    private final DecisionTransactionGuard transactionGuard;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger inFlightRequests = new AtomicInteger(0);
    private final Object drainLock = new Object();

    public SelfHostedDecisionEngine(
            SelfHostedProviderConfig config,
            HttpClient httpClient,
            ObjectMapper mapper,
            DecisionTransactionGuard transactionGuard) {
        this.config = Objects.requireNonNull(config, "config");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.redactor = new StateProjectionRedactor(mapper);
        this.transactionGuard = transactionGuard != null ? transactionGuard : DecisionTransactionGuard.system();
    }

    public SelfHostedDecisionEngine(SelfHostedProviderConfig config, HttpClient httpClient, ObjectMapper mapper) {
        this(config, httpClient, mapper, null);
    }

    @Override
    public ProviderCategory category() {
        return ProviderCategory.SELF_HOSTED;
    }

    public boolean isEnabled() {
        return config.enabled() && !closed.get();
    }

    public int inFlightRequests() {
        return inFlightRequests.get();
    }

    public SelfHostedProviderConfig config() {
        return config;
    }

    @Override
    public DecisionResult evaluate(DecisionRequest request) {
        Objects.requireNonNull(request, "request");

        // 1. Non-blocking Event-Loop Guard (§2.3, AGENTS.md)
        // This engine blocks on HttpClient#send, so it must never run on a Vert.x event-loop thread. The
        // check tests the actual executing thread rather than inferring it from the Vert.x context, because
        // @RunOnVirtualThread handlers retain an event-loop-derived context while running safely elsewhere.
        if (Context.isOnEventLoopThread()) {
            return new DecisionResult.NoOpinion(
                    request.decisionId(),
                    request.schemaVersion(),
                    NoOpinionReason.OVERLOAD_REJECTED,
                    null,
                    "Self-hosted decision evaluation must not execute on Vert.x event-loop thread");
        }

        // 2. Transaction Guard Check (§5.9)
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
                    "Self-hosted decision provider is disabled");
        }

        inFlightRequests.incrementAndGet();
        try {
            if (closed.get()) {
                return new DecisionResult.NoOpinion(
                        request.decisionId(),
                        request.schemaVersion(),
                        NoOpinionReason.DISABLED,
                        null,
                        "Self-hosted decision provider is disabled");
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
        // 4. Unconditional Loopback Endpoint Enforcement (§7.5, Issue 05)
        try {
            SelfHostedEndpointValidator.validateLoopbackEndpoint(config.endpoint());
        } catch (SecurityException | IllegalArgumentException _) {
            return DecisionResult.NoOpinion.ofMalformedResponse(
                    request.decisionId(), request.schemaVersion(), null, "Local-only endpoint validation failed");
        }

        // 5. Explicit Allow-listed State Projection and Redaction (§7.4, §7.5)
        StateProjectionPolicy projectionPolicy = resolveProjectionPolicy(request);
        if (projectionPolicy == null) {
            return DecisionResult.NoOpinion.ofMalformedResponse(
                    request.decisionId(),
                    request.schemaVersion(),
                    null,
                    "Missing allow-listed state projection policy for self-hosted provider");
        }

        String projectedStateJson;
        try {
            projectedStateJson = redactor.projectAndRedact(request.state(), projectionPolicy);
        } catch (Exception _) {
            return DecisionResult.NoOpinion.ofMalformedResponse(
                    request.decisionId(), request.schemaVersion(), null, "State projection or redaction failed");
        }
        if (DecisionToolRiskShadowClassifier.DECISION_ID.equals(request.decisionId())
                && !includesRequiredOperation(projectedStateJson)) {
            return DecisionResult.NoOpinion.ofMalformedResponse(
                    request.decisionId(),
                    request.schemaVersion(),
                    null,
                    "Tool-risk state projection must include a nonblank requiredOperation");
        }

        // 6. Map onto the System One wire contract. An unrepresentable question fails closed rather
        //    than emitting a request the provider would reject with 422. The profile comes from the
        //    endpoint's own configuration: the route and question vocabulary are shared, but `state`
        //    is JSON for Laya/Kev and prose for CLM, and sending the wrong one degrades answers
        //    silently rather than failing loudly.
        SystemOneWireMapper.Outcome mapped =
                SystemOneWireMapper.map(mapper, request, projectedStateJson, config.modelId(), config.wireProfile());

        // 7. Execute Outbound Loopback HTTP Call
        return switch (mapped) {
            case SystemOneWireMapper.Outcome.Ready ready -> executeHttpCall(request, ready.body());
            case SystemOneWireMapper.Outcome.Unrepresentable unrepresentable ->
                DecisionResult.NoOpinion.ofMalformedResponse(
                        request.decisionId(), request.schemaVersion(), null, unrepresentable.reason());
        };
    }

    private boolean includesRequiredOperation(String projectedStateJson) {
        try {
            Map<String, Object> projectedState = mapper.readValue(projectedStateJson, MAP_TYPE);
            Object requiredOperation = projectedState.get(ToolRiskShadowContext.REQUIRED_OPERATION_FIELD);
            return requiredOperation instanceof String operation && !operation.isBlank();
        } catch (JsonProcessingException _) {
            return false;
        }
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

        config.optionalLoopbackToken().ifPresent(token -> {
            switch (config.credentialHeaderStyle()) {
                case AMSHA_LOOPBACK_TOKEN -> reqBuilder.header(LOOPBACK_TOKEN_HEADER, token);
                case AUTHORIZATION_BEARER -> reqBuilder.header(AUTHORIZATION_HEADER, BEARER_PREFIX + token);
            }
        });

        HttpRequest httpRequest = reqBuilder.build();
        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            return handleHttpResponse(request, response);
        } catch (HttpTimeoutException _) {
            return new DecisionResult.NoOpinion(
                    request.decisionId(),
                    request.schemaVersion(),
                    NoOpinionReason.TIMEOUT,
                    null,
                    "Self-hosted provider request timed out after " + effectiveTimeout.toMillis() + "ms");
        } catch (ConnectException _) {
            return new DecisionResult.NoOpinion(
                    request.decisionId(),
                    request.schemaVersion(),
                    NoOpinionReason.MISSING_MODEL,
                    null,
                    "Self-hosted provider process connection refused");
        } catch (IOException _) {
            return new DecisionResult.NoOpinion(
                    request.decisionId(),
                    request.schemaVersion(),
                    NoOpinionReason.MISSING_MODEL,
                    null,
                    "Self-hosted provider transport error");
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return new DecisionResult.NoOpinion(
                    request.decisionId(),
                    request.schemaVersion(),
                    NoOpinionReason.TIMEOUT,
                    null,
                    "Self-hosted provider request interrupted");
        }
    }

    private DecisionResult handleHttpResponse(DecisionRequest request, HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == 200) {
            String body = response.body();
            if (body == null || body.isBlank()) {
                return DecisionResult.NoOpinion.ofMalformedResponse(
                        request.decisionId(),
                        request.schemaVersion(),
                        null,
                        "Self-hosted provider returned empty body");
            }
            try {
                Map<String, Object> rawMap = mapper.readValue(body, MAP_TYPE);
                return DecisionPayloadDecoder.decode(request, rawMap);
            } catch (JsonProcessingException _) {
                return DecisionResult.NoOpinion.ofMalformedResponse(
                        request.decisionId(), request.schemaVersion(), null, "Failed to parse provider response JSON");
            }
        }
        // 529 is the System One overload signal; 503 and 429 are the conventional equivalents.
        if (status == 503 || status == 429 || status == 529) {
            return new DecisionResult.NoOpinion(
                    request.decisionId(),
                    request.schemaVersion(),
                    NoOpinionReason.OVERLOAD_REJECTED,
                    null,
                    "Self-hosted provider returned overload/unavailable status " + status);
        }
        if (status == 408) {
            return new DecisionResult.NoOpinion(
                    request.decisionId(),
                    request.schemaVersion(),
                    NoOpinionReason.TIMEOUT,
                    null,
                    "Self-hosted provider returned timeout status 408");
        }
        if (status == 404 || status == 502) {
            return new DecisionResult.NoOpinion(
                    request.decisionId(),
                    request.schemaVersion(),
                    NoOpinionReason.MISSING_MODEL,
                    null,
                    "Self-hosted provider model/service unavailable (status " + status + ")");
        }
        return DecisionResult.NoOpinion.ofMalformedResponse(
                request.decisionId(),
                request.schemaVersion(),
                null,
                "Self-hosted provider error response with status " + status);
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
     *
     * <p>Lifecycle note: Does not close the injected {@link HttpClient} or {@link ObjectMapper},
     * as those instances may be shared across components and remain owned by their creator.
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
