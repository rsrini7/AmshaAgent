package com.amsha.core.routing;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;
import java.util.Optional;

/**
 * {@link ChatModel} backed by an {@link InfiniteLoopProvider} for slot rotation,
 * health tracking, and automatic failover across multiple LLM providers.
 */
public class InfiniteLoopChatModel implements ChatModel {

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
                (model, request) -> model.chat((ChatRequest) request),
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
        return provider.invoke(request);
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

    public List<Slot> slots() {
        return slots;
    }
}
