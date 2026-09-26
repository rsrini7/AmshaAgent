package com.amsha.core.classifier.selfhosted;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;
import com.amsha.classifier.api.DecisionEngine;
import com.amsha.classifier.api.DecisionPayloadDecoder;
import com.amsha.classifier.api.DecisionQuestion;
import com.amsha.classifier.api.DecisionRequest;
import com.amsha.classifier.api.DecisionResult;
import com.amsha.classifier.api.DecisionTransactionGuard;
import com.amsha.classifier.api.ProviderCategory;
import com.amsha.classifier.api.RedactionPolicy;
import com.amsha.classifier.api.SelfHostedDecisionProvider;
import com.amsha.classifier.api.StateProjectionPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

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
 *   <li>Defensive transaction guard prevents external HTTP calls inside active JTA transactions.</li>
 *   <li>Missing model, timeout, overload, and provider failure return deterministic fallback NoOpinion.</li>
 *   <li>Shutdown drains in-flight requests and rejects new evaluations with NoOpinion(DISABLED); injected client lifecycle remains with its creator.</li>
 * </ul>
 */
public final class SelfHostedDecisionEngine implements DecisionEngine, SelfHostedDecisionProvider, AutoCloseable {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Duration SHUTDOWN_DRAIN_TIMEOUT = Duration.ofSeconds(2);

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

        // 1. Transaction Guard Check (§5.9)
        if (transactionGuard.isInActiveTransaction()) {
            return new DecisionResult.NoOpinion(
                    request.decisionId(),
                    request.schemaVersion(),
                    NoOpinionReason.OVERLOAD_REJECTED,
                    null,
                    "Decision evaluation rejected: active JTA write transaction detected (§5.9)");
        }

        // 2. Disabled/Shutdown Check
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
        // 3. Unconditional Loopback Endpoint Enforcement (§7.5, Issue 05)
        try {
            SelfHostedEndpointValidator.validateLoopbackEndpoint(config.endpoint());
        } catch (SecurityException | IllegalArgumentException _) {
            return DecisionResult.NoOpinion.ofMalformedResponse(
                    request.decisionId(), request.schemaVersion(), null, "Local-only endpoint validation failed");
        }

        // 4. Explicit Allow-listed State Projection and Redaction (§7.4, §7.5)
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

        // 5. Serialize Wire Request Payload
        String requestBody;
        try {
            requestBody = buildWireRequestBody(request, projectedStateJson);
        } catch (JsonProcessingException _) {
            return DecisionResult.NoOpinion.ofMalformedResponse(
                    request.decisionId(), request.schemaVersion(), null, "Failed to serialize wire request payload");
        }

        // 6. Execute Outbound Loopback HTTP Call
        return executeHttpCall(request, requestBody);
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

    private String buildWireRequestBody(DecisionRequest request, String projectedStateJson)
            throws JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decisionId", request.decisionId());
        payload.put("schemaVersion", request.schemaVersion());
        payload.put("state", projectedStateJson);

        Map<String, Object> questionsMap = new LinkedHashMap<>();
        for (Map.Entry<String, DecisionQuestion<?>> entry : request.questions().entrySet()) {
            DecisionQuestion<?> q = entry.getValue();
            Map<String, Object> qData = new LinkedHashMap<>();
            qData.put("primitive", q.key().primitive().name());
            qData.put("criteria", q.criteria());
            if (!q.options().isEmpty()) {
                qData.put("options", q.options());
            }
            if (!q.rubricLevels().isEmpty()) {
                qData.put("rubricLevels", q.rubricLevels());
            }
            questionsMap.put(entry.getKey(), qData);
        }
        payload.put("questions", questionsMap);
        payload.put("timeoutMs", request.timeout().toMillis());

        return mapper.writeValueAsString(payload);
    }

    private DecisionResult executeHttpCall(DecisionRequest request, String requestBody) {
        Duration effectiveTimeout = calculateTimeout(request.timeout(), config.timeout());
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(config.endpoint())
                .timeout(effectiveTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));

        config.optionalLoopbackToken().ifPresent(token -> reqBuilder.header("X-Amsha-Loopback-Token", token));

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
        if (status == 503 || status == 429) {
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
