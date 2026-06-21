package com.amsha.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.List;

import com.amsha.core.routing.InfiniteLoopProvider;

/**
 * {@link EmbeddingModel} backed by an {@link InfiniteLoopProvider} for slot rotation,
 * health tracking, and automatic failover across multiple embedding providers.
 */
public class InfiniteLoopEmbeddingModel implements EmbeddingModel {

    private final InfiniteLoopProvider<EmbeddingModel, Response<Embedding>> provider;
    private final InfiniteLoopProvider<EmbeddingModel, Response<List<Embedding>>> batchProvider;

    public InfiniteLoopEmbeddingModel(
            List<InfiniteLoopProvider.Slot<EmbeddingModel>> slots,
            int cooldownBaseSeconds,
            int cooldownMaxSeconds,
            int cooldownTransientSeconds,
            long maxLatencyMs) {
        if (slots == null || slots.isEmpty()) {
            throw new IllegalArgumentException("InfiniteLoopEmbeddingModel requires at least one slot");
        }
        this.provider = new InfiniteLoopProvider<>(
                "embedding-single",
                slots,
                (model, request) -> model.embed((String) request),
                cooldownBaseSeconds,
                cooldownMaxSeconds,
                cooldownTransientSeconds,
                maxLatencyMs);
        this.batchProvider = new InfiniteLoopProvider<>(
                "embedding-batch",
                slots,
                (model, request) -> model.embedAll((List<TextSegment>) request),
                cooldownBaseSeconds,
                cooldownMaxSeconds,
                cooldownTransientSeconds,
                maxLatencyMs);
    }

    @Override
    public Response<Embedding> embed(String text) {
        return provider.invoke(text);
    }

    @Override
    public Response<Embedding> embed(TextSegment segment) {
        return provider.invoke(segment.text());
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        return batchProvider.invoke(segments);
    }

    /** Records a quality failure for the given slot. */
    public static void recordQualityFailure(String slotName) {
        InfiniteLoopProvider.recordQualityFailure(slotName);
    }

    /** Returns a summary of slot health for debugging. */
    public static String getSlotStats() {
        return InfiniteLoopProvider.getSlotStats();
    }
}
