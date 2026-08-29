package com.amsha.core.agent.reasoning.port;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.reasoning.error.LlmErrorClassificationService;
import com.amsha.core.routing.ModelRoute;
import com.amsha.core.routing.ModelRouter;
import com.amsha.core.routing.TurnRequirements;
import com.amsha.core.structured.StructuredOutputMetrics;
import com.amsha.llm.api.MultimodalCapabilities;
import com.amsha.llm.api.MultimodalCapability;
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

    @ConfigProperty(name = "amsha.llm.circuit-breaker.enabled", defaultValue = "true")
    boolean cbEnabled;

    /** Per-model circuit breaker adapters, keyed by pool key (provider#model). */
    private final ConcurrentHashMap<String, PerModelCircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

    @Inject
    public LangChain4jLlmAdapter(ChatModel globalChatModel, ModelRouter modelRouter, MeterRegistry meterRegistry) {
        this.globalChatModel = globalChatModel;
        this.modelRouter = modelRouter;
        this.meterRegistry = meterRegistry;
    }

    public LangChain4jLlmAdapter(ChatModel globalChatModel, ModelRouter modelRouter) {
        this(globalChatModel, modelRouter, null);
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
    public MultimodalCapability multimodalCapability(String agentRole) {
        ChatModel resolvedModel = resolveModel(agentRole, false);
        return MultimodalCapabilities.resolveCapability(resolvedModel);
    }

    @Override
    public ModelRoute resolveRoute(String agentRole, TurnRequirements requirements) {
        // Slab A provisional: TurnRequirements are accepted at the port seam but modality selection
        // is not yet applied (Slab B wires a ModelRouteSelector). Resolution falls back to the
        // role-based path, returning a non-turn-aware route for downstream compatibility.
        ChatModel resolvedModel = resolveModel(agentRole, false);
        MultimodalCapability capability = MultimodalCapabilities.resolveCapability(resolvedModel);
        return new ModelRoute(
                "role:" + (agentRole == null ? "global" : agentRole),
                null,
                null,
                null,
                resolvedModel,
                capability,
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
