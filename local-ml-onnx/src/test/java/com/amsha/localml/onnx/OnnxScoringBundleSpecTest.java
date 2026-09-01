package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class OnnxScoringBundleSpecTest {

    @Test
    void validSpecCreation() {
        OnnxScoringBundleSpec spec = new OnnxScoringBundleSpec(
                "text-pair",
                "v1",
                "input_ids",
                "attention_mask",
                "token_type_ids",
                "logits",
                384,
                0L,
                "relevance",
                OnnxScoringBundleSpec.ScoreDirection.HIGHER_IS_BETTER,
                OnnxScoringBundleSpec.OutputShape.BATCH_BY_ONE,
                List.of("probe1", "probe2"));

        assertEquals("text-pair", spec.inputType());
        assertEquals("v1", spec.tokenizerRevision());
        assertEquals("input_ids", spec.inputIdsName());
        assertEquals("logits", spec.outputName());
        assertEquals(384, spec.maxSequenceLength());
        assertEquals(0L, spec.paddingTokenId());
        assertEquals(OnnxScoringBundleSpec.ScoreDirection.HIGHER_IS_BETTER, spec.scoreDirection());
        assertEquals(OnnxScoringBundleSpec.OutputShape.BATCH_BY_ONE, spec.expectedOutputShape());
    }

    @Test
    void rejectsInvalidInputType() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new OnnxScoringBundleSpec(
                        "single-text",
                        "v1",
                        "input_ids",
                        "attention_mask",
                        null,
                        "logits",
                        384,
                        0L,
                        "relevance",
                        OnnxScoringBundleSpec.ScoreDirection.HIGHER_IS_BETTER,
                        OnnxScoringBundleSpec.OutputShape.BATCH_BY_ONE,
                        List.of("probe1")));
    }

    @Test
    void rejectsLowerIsBetterDirection() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new OnnxScoringBundleSpec(
                        "text-pair",
                        "v1",
                        "input_ids",
                        "attention_mask",
                        null,
                        "logits",
                        384,
                        0L,
                        "relevance",
                        OnnxScoringBundleSpec.ScoreDirection.LOWER_IS_BETTER,
                        OnnxScoringBundleSpec.OutputShape.BATCH_BY_ONE,
                        List.of("probe1")));
    }

    @Test
    void rejectsDuplicateTensorNames() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new OnnxScoringBundleSpec(
                        "text-pair",
                        "v1",
                        "shared_name",
                        "shared_name",
                        null,
                        "logits",
                        384,
                        0L,
                        "relevance",
                        OnnxScoringBundleSpec.ScoreDirection.HIGHER_IS_BETTER,
                        OnnxScoringBundleSpec.OutputShape.BATCH_BY_ONE,
                        List.of("probe1")));
    }

    @Test
    void rejectsInvalidSequenceLengthAndPadding() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new OnnxScoringBundleSpec(
                        "text-pair",
                        "v1",
                        "input_ids",
                        "attention_mask",
                        null,
                        "logits",
                        0,
                        0L,
                        "relevance",
                        OnnxScoringBundleSpec.ScoreDirection.HIGHER_IS_BETTER,
                        OnnxScoringBundleSpec.OutputShape.BATCH_BY_ONE,
                        List.of("probe1")));

        assertThrows(
                IllegalArgumentException.class,
                () -> new OnnxScoringBundleSpec(
                        "text-pair",
                        "v1",
                        "input_ids",
                        "attention_mask",
                        null,
                        "logits",
                        384,
                        -1L,
                        "relevance",
                        OnnxScoringBundleSpec.ScoreDirection.HIGHER_IS_BETTER,
                        OnnxScoringBundleSpec.OutputShape.BATCH_BY_ONE,
                        List.of("probe1")));
    }

    @Test
    void rejectsEmptyWarmupProbes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new OnnxScoringBundleSpec(
                        "text-pair",
                        "v1",
                        "input_ids",
                        "attention_mask",
                        null,
                        "logits",
                        384,
                        0L,
                        "relevance",
                        OnnxScoringBundleSpec.ScoreDirection.HIGHER_IS_BETTER,
                        OnnxScoringBundleSpec.OutputShape.BATCH_BY_ONE,
                        List.of()));
    }
}
