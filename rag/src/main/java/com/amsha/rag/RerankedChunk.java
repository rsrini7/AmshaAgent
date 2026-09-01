package com.amsha.rag;

import java.util.Objects;

/**
 * Represents a retrieved chunk augmented with a model-local reranker score,
 * while strictly preserving the synthetic RRF fusion score on {@link #chunk()}.
 *
 * <p>Normative contract (Issue 04):
 * <ul>
 *   <li>{@code chunk.score()} remains the synthetic RRF fusion score byte-for-byte.</li>
 *   <li>{@code rerankScore} is the model-local cross-encoder score used purely for
 *       reordering candidates within the retrieved window. It is NOT a calibrated [0, 1]
 *       probability and must not be used as an absolute cross-model cutoff threshold.</li>
 * </ul>
 */
public record RerankedChunk(ScoredChunk chunk, double rerankScore) {

    public RerankedChunk {
        Objects.requireNonNull(chunk, "chunk");
    }

    public String chunkId() {
        return chunk.chunkId();
    }

    public String text() {
        return chunk.text();
    }

    public double score() {
        return chunk.score();
    }
}
