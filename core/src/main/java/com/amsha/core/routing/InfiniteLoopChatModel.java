package com.amsha.core.routing;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import com.amsha.core.structured.StructuredOutputMetrics;
import com.amsha.llm.api.StrictJsonSchemaCapable;
import com.amsha.llm.api.StructuredSchemaRequests;
import com.amsha.llm.api.error.LlmMalformedResponseException;

import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * {@link ChatModel} backed by an {@link InfiniteLoopProvider} for slot rotation,
 * health tracking, and automatic failover across multiple LLM providers.
 */
public class InfiniteLoopChatModel implements ChatModel, StrictJsonSchemaCapable {

    private static final Logger log = Logger.getLogger(InfiniteLoopChatModel.class);
    private static final ScopedValue<AtomicBoolean> SCHEMA_REJECTED = ScopedValue.newInstance();

    public record Slot(String name, ChatModel model, String baseUrl) {
        public Slot(String name, ChatModel model) {
            this(name, model, "unknown");
        }
    }

    private final InfiniteLoopProvider<ChatModel, ChatResponse> provider;
    private final List<Slot> slots;

    public InfiniteLoopChatModel(
            String loopId,
            List<Slot> slots,
            int cooldownBaseSeconds,
            int cooldownMaxSeconds,
            int cooldownTransientSeconds,
            long maxLatencyMs) {
        this(loopId, slots, cooldownBaseSeconds, cooldownMaxSeconds, cooldownTransientSeconds, maxLatencyMs, null);
    }

    public InfiniteLoopChatModel(
            String loopId,
            List<Slot> slots,
            int cooldownBaseSeconds,
            int cooldownMaxSeconds,
            int cooldownTransientSeconds,
            long maxLatencyMs,
            MeterRegistry meterRegistry) {
        if (slots == null || slots.isEmpty()) {
            throw new IllegalArgumentException("InfiniteLoopChatModel requires at least one slot");
        }
        this.slots = List.copyOf(slots);
        List<InfiniteLoopProvider.Slot<ChatModel>> providerSlots = slots.stream()
                .map(s -> new InfiniteLoopProvider.Slot<>(s.name(), s.model(), s.baseUrl()))
                .toList();
        this.provider = new InfiniteLoopProvider<>(
                loopId,
                providerSlots,
                (model, request) -> {
                    ChatRequest req = (ChatRequest) request;
                    boolean schemaRejected =
                            SCHEMA_REJECTED.isBound() && SCHEMA_REJECTED.get().get();
                    boolean candidateStrict = !schemaRejected
                            && model instanceof StrictJsonSchemaCapable capable
                            && capable.supportsStrictJsonSchema();
                    ChatRequest outbound = StructuredSchemaRequests.forCandidate(req, candidateStrict);
                    boolean schemaAttached = StructuredSchemaRequests.hasNativeJsonSchema(outbound);
                    try {
                        return model.chat(outbound);
                    } catch (LlmMalformedResponseException mre) {
                        if (schemaAttached) {
                            StructuredOutputMetrics.recordSchemaRejection(meterRegistry, "infinite-loop");
                            if (SCHEMA_REJECTED.isBound()) {
                                SCHEMA_REJECTED.get().set(true);
                            }
                            log.debug(
                                    "InfiniteLoopChatModel: native schema rejected by candidate; downgrading to prompt directive");
                            return model.chat(StructuredSchemaRequests.withoutNativeJsonSchema(req));
                        }
                        throw mre;
                    } catch (Throwable t) {
                        if (schemaAttached && SCHEMA_REJECTED.isBound()) {
                            SCHEMA_REJECTED.get().set(true);
                        }
                        throw t;
                    }
                },
                cooldownBaseSeconds,
                cooldownMaxSeconds,
                cooldownTransientSeconds,
                maxLatencyMs);
    }

    public InfiniteLoopChatModel(
            String loopId,
            List<Slot> slots,
            int cooldownBaseSeconds,
            int cooldownMaxSeconds,
            int cooldownTransientSeconds) {
        this(loopId, slots, cooldownBaseSeconds, cooldownMaxSeconds, cooldownTransientSeconds, 60_000L);
    }

    public InfiniteLoopChatModel(
            String loopId,
            List<Slot> slots,
            int cooldownBaseSeconds,
            int cooldownMaxSeconds,
            int cooldownTransientSeconds,
            MeterRegistry meterRegistry) {
        this(loopId, slots, cooldownBaseSeconds, cooldownMaxSeconds, cooldownTransientSeconds, 60_000L, meterRegistry);
    }

    public InfiniteLoopChatModel(
            List<Slot> slots,
            int cooldownBaseSeconds,
            int cooldownMaxSeconds,
            int cooldownTransientSeconds,
            long maxLatencyMs) {
        this("agent-chat", slots, cooldownBaseSeconds, cooldownMaxSeconds, cooldownTransientSeconds, maxLatencyMs);
    }

    public InfiniteLoopChatModel(
            List<Slot> slots, int cooldownBaseSeconds, int cooldownMaxSeconds, int cooldownTransientSeconds) {
        this("agent-chat", slots, cooldownBaseSeconds, cooldownMaxSeconds, cooldownTransientSeconds, 60_000L);
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        AtomicBoolean rejected = new AtomicBoolean(false);
        try {
            return ScopedValue.where(SCHEMA_REJECTED, rejected).call(() -> provider.invoke(request));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Returns the slot name that served the most recent chat() call in this scope. */
    public static Optional<String> getLastUsedSlot() {
        return InfiniteLoopProvider.getLastUsedSlot();
    }

    /** Records a quality failure for the given slot. */
    public static void recordQualityFailure(String slotName) {
        InfiniteLoopProvider.recordQualityFailure(slotName);
    }

    /** Returns a summary of slot health for debugging. */
    public static String getSlotStats() {
        return InfiniteLoopProvider.getSlotStats();
    }

    @Override
    public boolean supportsStrictJsonSchema() {
        return !slots.isEmpty()
                && slots.stream()
                        .allMatch(s -> s.model() instanceof StrictJsonSchemaCapable capable
                                && capable.supportsStrictJsonSchema());
    }

    public List<Slot> slots() {
        return slots;
    }
}
