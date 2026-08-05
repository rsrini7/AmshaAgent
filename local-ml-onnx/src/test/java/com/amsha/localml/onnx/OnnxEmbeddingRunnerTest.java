package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class OnnxEmbeddingRunnerTest {

    @Test
    void maskedMeanExcludesPaddingAndNormalizes() throws Exception {
        List<float[]> vectors = OnnxEmbeddingRunner.pool(
                new float[][][] {{{3, 0}, {0, 4}, {99, 99}}},
                new long[][] {{1, 1, 0}},
                spec(OnnxEmbeddingBundleSpec.PoolingStrategy.MASKED_MEAN, true));

        assertEquals(1, vectors.size());
        assertArrayEquals(new float[] {0.6f, 0.8f}, vectors.getFirst(), 0.00001f);
    }

    @Test
    void directVectorRejectsWrongOutputDimension() {
        assertThrows(
                OnnxEmbeddingInferenceException.class,
                () -> OnnxEmbeddingRunner.pool(
                        new float[][] {{1, 2, 3}},
                        new long[][] {{1}},
                        spec(OnnxEmbeddingBundleSpec.PoolingStrategy.DIRECT_VECTOR, false)));
    }

    @Test
    void maskedMeanRejectsAllZeroMask() {
        assertThrows(
                OnnxEmbeddingInferenceException.class,
                () -> OnnxEmbeddingRunner.pool(
                        new float[][][] {{{1, 2}}},
                        new long[][] {{0}},
                        spec(OnnxEmbeddingBundleSpec.PoolingStrategy.MASKED_MEAN, false)));
    }

    private static OnnxEmbeddingBundleSpec spec(
            OnnxEmbeddingBundleSpec.PoolingStrategy poolingStrategy, boolean normalize) {
        return new OnnxEmbeddingBundleSpec(
                "v1",
                "input_ids",
                "attention_mask",
                null,
                "last_hidden_state",
                8,
                OnnxEmbeddingBundleSpec.SUPPORTED_CHUNKING_STRATEGY,
                OnnxEmbeddingBundleSpec.SUPPORTED_CHUNKING_REVISION,
                8,
                0,
                OnnxEmbeddingBundleSpec.SUPPORTED_MULTI_WINDOW_AGGREGATION,
                2,
                0L,
                poolingStrategy,
                normalize,
                "example-space",
                "v1",
                List.of("warm-up"));
    }
}
