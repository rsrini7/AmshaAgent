package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class OnnxScoringRunnerTest {

    @Test
    void extractScoresBatchByOne() throws Exception {
        float[][] output = new float[][] {{0.85f}, {0.12f}, {-0.45f}};
        double[] scores =
                OnnxScoringRunner.extractScores(output, OnnxScoringBundleSpec.OutputShape.BATCH_BY_ONE, "relevance", 3);

        assertEquals(3, scores.length);
        assertArrayEquals(new double[] {0.85, 0.12, -0.45}, scores, 0.0001);
    }

    @Test
    void extractScoresBatchByOneRejectsExtraColumns() {
        float[][] output = new float[][] {{0.85f, 0.20f}};
        assertThrows(
                Exception.class,
                () -> OnnxScoringRunner.extractScores(
                        output, OnnxScoringBundleSpec.OutputShape.BATCH_BY_ONE, "relevance", 1));
    }

    @Test
    void extractScoresOneDimensional() throws Exception {
        float[] output = new float[] {0.99f, 0.42f};
        double[] scores = OnnxScoringRunner.extractScores(
                output, OnnxScoringBundleSpec.OutputShape.ONE_DIMENSIONAL, "relevance", 2);

        assertEquals(2, scores.length);
        assertArrayEquals(new double[] {0.99, 0.42}, scores, 0.0001);
    }

    @Test
    void extractScoresBatchByTwoWithOutputLabel() throws Exception {
        float[][] output = new float[][] {{0.1f, 0.9f}, {0.8f, 0.2f}};
        double[] scoresPos =
                OnnxScoringRunner.extractScores(output, OnnxScoringBundleSpec.OutputShape.BATCH_BY_TWO, "relevance", 2);

        assertEquals(2, scoresPos.length);
        assertArrayEquals(new double[] {0.9, 0.2}, scoresPos, 0.0001);

        double[] scoresNeg =
                OnnxScoringRunner.extractScores(output, OnnxScoringBundleSpec.OutputShape.BATCH_BY_TWO, "negative", 2);
        assertEquals(2, scoresNeg.length);
        assertArrayEquals(new double[] {0.1, 0.8}, scoresNeg, 0.0001);
    }

    @Test
    void extractScoresBatchByTwoRejectsUnknownOutputLabel() {
        float[][] output = new float[][] {{0.1f, 0.9f}};
        assertThrows(
                Exception.class,
                () -> OnnxScoringRunner.extractScores(
                        output, OnnxScoringBundleSpec.OutputShape.BATCH_BY_TWO, "unknown_label", 1));
    }

    @Test
    void extractScoresBatchByTwoRejectsExtraColumns() {
        float[][] output = new float[][] {{0.1f, 0.9f, 0.5f}};
        assertThrows(
                Exception.class,
                () -> OnnxScoringRunner.extractScores(
                        output, OnnxScoringBundleSpec.OutputShape.BATCH_BY_TWO, "relevance", 1));
    }

    @Test
    void extractScoresRejectsMismatchedCount() {
        float[][] output = new float[][] {{0.85f}};
        assertThrows(
                Exception.class,
                () -> OnnxScoringRunner.extractScores(
                        output, OnnxScoringBundleSpec.OutputShape.BATCH_BY_ONE, "relevance", 2));
    }

    @Test
    void extractScoresRejectsNonFinite() {
        float[][] output = new float[][] {{Float.NaN}};
        assertThrows(
                Exception.class,
                () -> OnnxScoringRunner.extractScores(
                        output, OnnxScoringBundleSpec.OutputShape.BATCH_BY_ONE, "relevance", 1));
    }
}
