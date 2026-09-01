package com.amsha.core.agent.reasoning.port;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.reasoning.error.LlmErrorClassificationService;
import com.amsha.core.routing.DefaultModelRouteSelector;
import com.amsha.core.routing.ModelRoute;
import com.amsha.core.routing.ModelRouteSelector;
import com.amsha.core.routing.ModelRouter;
import com.amsha.core.routing.ProfileRouteCandidateBuilder;
import com.amsha.core.routing.TurnRequirements;
import com.amsha.core.structured.StructuredOutputMetrics;
import com.amsha.llm.api.MultimodalCapabilities;
import com.amsha.llm.api.MultimodalCapability;
import com.amsha.llm.api.MultimodalRequests;
import com.amsha.llm.api.StrictJsonSchemaCapable;
import com.amsha.llm.api.StructuredSchemaRequests;
import com.amsha.llm.api.error.LlmMalformedResponseException;
import com.amsha.llm.api.error.LlmToolCallException;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Routes LLM requests to the correct ChatModel based on agent role,
 * with per-model circuit breaker isolation to prevent cross-provider cascade failures.
 *
 * <p>Circuit breakers are scoped per pool key ({@code provider#model}) via
 * separate adapter instances managed by {@link PerModelCircuitBreaker}.
 * A failure in Groq will NOT trip the circuit for OpenAI or Gemini.
 */
@ApplicationScoped
public class LangChain4jLlmAdapter implements LlmProviderPort {

    private static final Logger log = Logger.getLogger(LangChain4jLlmAdapter.class);

    private final ChatModel globalChatModel;
    private final ModelRouter modelRouter;
    private final MeterRegistry meterRegistry;
    private final ModelRouteSelector modelRouteSelector;
    private final ProfileRouteCandidateBuilder candidateBuilder;

    @ConfigProperty(name = "amsha.llm.circuit-breaker.enabled", defaultValue = "true")
    boolean cbEnabled;

    /** Per-model circuit breaker adapters, keyed by pool key (provider#model). */
    private final ConcurrentHashMap<String, PerModelCircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

    @Inject
    public LangChain4jLlmAdapter(
            ChatModel globalChatModel,
            ModelRouter modelRouter,
            MeterRegistry meterRegistry,
            ModelRouteSelector modelRouteSelector,
            ProfileRouteCandidateBuilder candidateBuilder) {
        this.globalChatModel = globalChatModel;
        this.modelRouter = modelRouter;
        this.meterRegistry = meterRegistry;
        this.modelRouteSelector = modelRouteSelector;
        this.candidateBuilder = candidateBuilder;
    }

    public LangChain4jLlmAdapter(ChatModel globalChatModel, ModelRouter modelRouter) {
        this(globalChatModel, modelRouter, null, new DefaultModelRouteSelector(), null);
    }

    @Override
    public ChatResponse nextStep(ChatRequest request) {
        return nextStep(request, null);
    }

    @Override
    public ChatResponse nextStep(ChatRequest request, String agentRole) {
        return nextStep(request, agentRole, false);
    }

    @Override
    public ChatResponse nextStep(ChatRequest request, String agentRole, String schemaJson) {
        ChatModel resolvedModel = resolveModel(agentRole, false);
        ChatRequest outbound = withNativeSchemaIfCapable(request, resolvedModel, schemaJson);
        try {
            return execute(outbound, resolvedModel);
        } catch (LlmMalformedResponseException e) {
            if (!StructuredSchemaRequests.hasNativeJsonSchema(outbound)) {
                throw e;
            }
            StructuredOutputMetrics.recordSchemaRejection(meterRegistry, "agent");
            log.debug("Native schema rejected; retrying the same routed model with prompt-only structured output");
            return execute(StructuredSchemaRequests.withoutNativeJsonSchema(outbound), resolvedModel);
        }
    }

    @Override
    public ChatResponse nextStep(ChatRequest request, String agentRole, boolean bypassLightweightRouting) {
        ChatModel resolvedModel = resolveModel(agentRole, bypassLightweightRouting);
        return execute(request, resolvedModel);
    }

    @Override
    public ChatResponse nextStep(ChatRequest request, String agentRole, ModelRoute route) {
        if (route == null) {
            return nextStep(request, agentRole);
        }
        List<ModelRoute> candidates = new ArrayList<>();
        candidates.add(route);
        candidates.addAll(route.fallbackRoutes());
        RuntimeException firstFailure = null;
        Set<ChatModel> attemptedModels = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ModelRoute candidate : candidates) {
            if (candidate == null || candidate.model() == null) {
                continue;
            }
            if (!MultimodalRequests.supportsAllMultimodalContent(request, candidate.capability())) {
                log.debugf(
                        "[LLM-ADAPTER] Skipping route candidate %s; it cannot consume this turn's media",
                        candidate.routeId());
                continue;
            }
            if (!attemptedModels.add(candidate.model())) {
                continue;
            }
            try {
                ChatRequest candidateRequest =
                        MultimodalRequests.forCandidate(request, candidate.capability(), candidate.modelName());
                return execute(candidateRequest, candidate.model());
            } catch (RuntimeException e) {
                if (firstFailure == null) {
                    firstFailure = e;
                }
                log.warnf(
                        "[LLM-ADAPTER] Route candidate %s failed; trying next same-modality candidate: %s",
                        candidate.routeId(), LogUtil.rootMessage(e));
            }
        }
        if (MultimodalRequests.hasMultimodalContent(request)) {
            return executeTextFallback(request, agentRole, attemptedModels, firstFailure);
        }
        if (firstFailure == null) {
            ChatRequest fallbackRequest =
                    MultimodalRequests.forCandidate(request, route.capability(), route.modelName());
            return nextStep(fallbackRequest, agentRole);
        }
        throw firstFailure;
    }

    private ChatResponse executeTextFallback(
            ChatRequest request, String agentRole, Set<ChatModel> attemptedModels, RuntimeException firstFailure) {
        ChatRequest textRequest = MultimodalRequests.withoutMultimodalContent(request, "text-only fallback");
        ModelRoute textRoute = resolveRoute(agentRole, TurnRequirements.textOnly());
        List<ModelRoute> textCandidates = new ArrayList<>();
        if (textRoute != null) {
            textCandidates.add(textRoute);
            textCandidates.addAll(textRoute.fallbackRoutes());
        }
        RuntimeException textFailure = null;
        for (ModelRoute candidate : textCandidates) {
            if (candidate == null || candidate.model() == null || attemptedModels.contains(candidate.model())) {
                continue;
            }
            try {
                return execute(textRequest, candidate.model());
            } catch (RuntimeException e) {
                if (textFailure == null) {
                    textFailure = e;
                }
                log.warnf(
                        "[LLM-ADAPTER] Text fallback route %s failed: %s", candidate.routeId(), LogUtil.rootMessage(e));
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
        if (textFailure != null) {
            throw textFailure;
        }
        throw new IllegalStateException("No compatible text route is available for multimodal request");
    }

    @Override
    public ChatResponse nextStep(
            ChatRequest request, String agentRole, ModelRoute route, boolean bypassLightweightRouting) {
        return nextStep(request, agentRole, route);
    }

    @Override
    public MultimodalCapability multimodalCapability(String agentRole) {
        ChatModel resolvedModel = resolveModel(agentRole, false);
        return MultimodalCapabilities.resolveCapability(resolvedModel);
    }

    @Override
    public ModelRoute resolveRoute(String agentRole, TurnRequirements requirements) {
        // Slab B: Deep resolution — enumerate profile candidates, filter by TurnRequirements,
        // select via ModelRouteSelector. Falls back to role-based resolution when no profile
        // candidates exist or when the selector cannot satisfy the requirements.
        if (candidateBuilder != null) {
            List<ModelRoute> candidates = candidateBuilder.buildCandidates(agentRole);
            if (!candidates.isEmpty()) {
                Optional<ModelRoute> selected = modelRouteSelector.select(agentRole, requirements, candidates);
                if (selected.isPresent()) {
                    log.debugf(
                            "[LLM-ADAPTER] Slab B route selected for role=%s: %s",
                            agentRole, selected.get().routeId());
                    return selected.get();
                }
                log.debugf(
                        "[LLM-ADAPTER] Slab B selector returned empty for role=%s with %d candidates; "
                                + "falling back to role-based route",
                        agentRole, candidates.size());
            }
        }
        // Fallback: role-based resolution (pre-Slab B path). Build a single-candidate route
        // from the legacy ModelRouter and pass it through the selector for consistent ordering.
        ChatModel resolvedModel = resolveModel(agentRole, false);
        MultimodalCapability capability = MultimodalCapabilities.resolveCapability(resolvedModel);
        ModelRoute legacyRoute = new ModelRoute(
                "role:" + (agentRole == null ? "global" : agentRole),
                null,
                null,
                null,
                resolvedModel,
                capability,
                List.of(),
                0L);
        Optional<ModelRoute> selected = modelRouteSelector.select(agentRole, requirements, List.of(legacyRoute));
        if (selected.isPresent()) {
            return selected.get();
        }
        log.warnf(
                "[LLM-ADAPTER] Legacy route %s does not cover turn requirements; degrading to text-only fallback",
                legacyRoute.routeId());
        return new ModelRoute(
                "fallback:text-only",
                legacyRoute.providerName(),
                legacyRoute.modelName(),
                null,
                resolvedModel,
                MultimodalCapability.TEXT_ONLY,
                List.of(),
                0L);
    }

    private ChatResponse execute(ChatRequest request, ChatModel resolvedModel) {

        if (!cbEnabled) {
            return resolvedModel.chat(request);
        }

        // Use per-model circuit breaker to isolate failures across providers
        String poolKey = derivePoolKey(resolvedModel);
        PerModelCircuitBreaker cb = circuitBreakers.computeIfAbsent(poolKey, k -> new PerModelCircuitBreaker(poolKey));
        return cb.execute(request, resolvedModel);
    }

    private ChatRequest withNativeSchemaIfCapable(ChatRequest request, ChatModel model, String schemaJson) {
        if (schemaJson == null
                || schemaJson.isBlank()
                || !(model instanceof StrictJsonSchemaCapable capable)
                || !capable.supportsStrictJsonSchema()) {
            return request;
        }
        try {
            return StructuredSchemaRequests.withNativeJsonSchema(request, "structured-output", schemaJson);
        } catch (IllegalArgumentException e) {
            log.debugf(
                    "Native schema attachment skipped; using prompt-only structured output: %s",
                    LogUtil.rootMessage(e));
            return request;
        }
    }

    private ChatModel resolveModel(String agentRole, boolean bypassLightweightRouting) {
        if (agentRole == null || agentRole.isBlank()) {
            return globalChatModel;
        }
        try {
            return modelRouter.resolve(agentRole, bypassLightweightRouting);
        } catch (Exception e) {
            log.warnf(
                    "[LLM-ADAPTER] Model resolution failed for role=%s, falling back to global: %s",
                    agentRole, LogUtil.rootMessage(e));
            return globalChatModel;
        }
    }

    /**
     * Derives a human-readable pool key for circuit breaker scoping.
     * Uses the model's toString() as a fallback if it's not a known type.
     */
    private String derivePoolKey(ChatModel model) {
        if (model == globalChatModel) {
            return "global";
        }
        // Use class name + hash to distinguish model instances
        return model.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(model));
    }

    /**
     * Per-model circuit breaker that wraps LLM calls with fault tolerance.
     * Each instance is scoped to a single model pool key, preventing
     * cross-provider cascade failures.
     *
     * <p>Uses SmallRye Fault Tolerance programmatic API rather than
     * annotation-based CDI proxies, which cannot be scoped per-key.
     */
    static class PerModelCircuitBreaker {
        private static final Logger log = Logger.getLogger(PerModelCircuitBreaker.class);

        private final String poolKey;

        enum State {
            CLOSED,
            OPEN,
            HALF_OPEN
        }

        // Circuit state
        private State state = State.CLOSED;
        private int failureCount;
        private int successCount;
        private int requestCount;
        private long openSince;

        private static final int REQUEST_VOLUME_THRESHOLD = 4;
        private static final double FAILURE_RATIO = 0.5;
        private static final long DELAY_MS = 10_000;
        private static final int SUCCESS_THRESHOLD = 2;

        PerModelCircuitBreaker(String poolKey) {
            this.poolKey = poolKey;
        }

        synchronized ChatResponse execute(ChatRequest request, ChatModel model) {
            if (state == State.OPEN) {
                long elapsed = System.currentTimeMillis() - openSince;
                if (elapsed >= DELAY_MS) {
                    log.infof("[CB] Half-open for %s — retrying after %dms", poolKey, elapsed);
                    state = State.HALF_OPEN;
                    requestCount = 0;
                    failureCount = 0;
                    successCount = 0;
                } else {
                    throw new RuntimeException(
                            "Circuit breaker OPEN for " + poolKey + " (retry in " + (DELAY_MS - elapsed) + "ms)");
                }
            }

            requestCount++;
            try {
                ChatResponse response = model.chat(request);
                onSuccess();
                return response;
            } catch (LlmMalformedResponseException | LlmToolCallException e) {
                // Skip — these are not infrastructure failures
                throw e;
            } catch (Exception e) {
                onFailure();
                throw LlmErrorClassificationService.classify(e);
            }
        }

        private synchronized void onSuccess() {
            if (state == State.HALF_OPEN) {
                successCount++;
                if (successCount >= SUCCESS_THRESHOLD) {
                    log.infof("[CB] Closed for %s — recovered after %d successes", poolKey, successCount);
                    state = State.CLOSED;
                    failureCount = 0;
                    requestCount = 0;
                }
            } else if (state == State.CLOSED) {
                successCount++;
            }
        }

        private synchronized void onFailure() {
            failureCount++;
            if (state == State.HALF_OPEN) {
                log.warnf("[CB] Opened for %s during half-open due to failure", poolKey);
                state = State.OPEN;
                openSince = System.currentTimeMillis();
            } else if (state == State.CLOSED) {
                if (requestCount >= REQUEST_VOLUME_THRESHOLD
                        && ((double) failureCount / requestCount) >= FAILURE_RATIO) {
                    log.warnf(
                            "[CB] Opened for %s — failureRate=%.2f (%d/%d)",
                            poolKey, (double) failureCount / requestCount, failureCount, requestCount);
                    state = State.OPEN;
                    openSince = System.currentTimeMillis();
                }
            }
        }
    }
}
