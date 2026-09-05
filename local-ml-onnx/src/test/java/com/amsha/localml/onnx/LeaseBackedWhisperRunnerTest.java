package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class LeaseBackedWhisperRunnerTest {

    private static final String VALID_SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void canonicalTokenConstantsMatchWhisperSpecification() {
        assertEquals(50257L, LeaseBackedWhisperRunner.SOT_TOKEN_ID);
        assertEquals(50258L, LeaseBackedWhisperRunner.ENGLISH_TOKEN_ID);
        assertEquals(50358L, LeaseBackedWhisperRunner.TRANSCRIBE_TOKEN_ID);
        assertEquals(50362L, LeaseBackedWhisperRunner.NO_TIMESTAMPS_TOKEN_ID);
    }

    @Test
    void defaultPrefixGeneratesExpectedTokens() {
        List<Long> englishOnlyPrefix = LeaseBackedWhisperRunner.defaultPrefix(true);
        assertEquals(List.of(50257L, 50362L), englishOnlyPrefix);

        List<Long> multilingualPrefix = LeaseBackedWhisperRunner.defaultPrefix(false);
        assertEquals(List.of(50257L, 50258L, 50358L, 50362L), multilingualPrefix);
    }

    @Test
    void constructorThreadsSpecAndInitialPrefixTokens() {
        OnnxAudioTranscriptionBundleSpec spec = new OnnxAudioTranscriptionBundleSpec(
                "decoder_model.onnx",
                VALID_SHA,
                1024L,
                "preprocessor_config.json",
                VALID_SHA,
                "config.json",
                VALID_SHA,
                16000,
                1,
                80,
                400,
                160,
                30,
                448,
                true);

        OnnxSessionLeaseManager leaseManager = mock(OnnxSessionLeaseManager.class);
        VerifiedOnnxModelBundle encBundle = mock(VerifiedOnnxModelBundle.class);
        VerifiedOnnxModelBundle decBundle = mock(VerifiedOnnxModelBundle.class);

        LeaseBackedWhisperRunner runnerWithSpec = new LeaseBackedWhisperRunner(
                leaseManager,
                encBundle,
                decBundle,
                spec,
                List.of(50257L, 50258L, 50358L, 50362L),
                Set.of(1, 2),
                Set.of(3, 4),
                50256);

        assertEquals(spec, runnerWithSpec.spec());
        assertEquals(List.of(50257L, 50258L, 50358L, 50362L), runnerWithSpec.initialPrefixTokens());

        LeaseBackedWhisperRunner runnerDefaults =
                new LeaseBackedWhisperRunner(leaseManager, encBundle, decBundle, null, null, Set.of(), Set.of(), 50256);

        assertNull(runnerDefaults.spec());
        assertEquals(List.of(50257L, 50362L), runnerDefaults.initialPrefixTokens());

        LeaseBackedWhisperRunner runnerCompat =
                new LeaseBackedWhisperRunner(leaseManager, encBundle, decBundle, Set.of(), Set.of(), 50256);

        assertNull(runnerCompat.spec());
        assertEquals(List.of(50257L, 50362L), runnerCompat.initialPrefixTokens());
    }

    @Test
    void pickBestTokenSelectsArgmaxWithoutMutatingLogits() {
        float[] logits = new float[] {0.1f, 0.9f, 0.4f, 0.2f};
        float[] originalLogits = Arrays.copyOf(logits, logits.length);

        int best = LeaseBackedWhisperRunner.pickBestToken(logits, false, Set.of(), Set.of());
        assertEquals(1, best);
        assertArrayEquals(originalLogits, logits, "Input logits buffer must remain unmodified");
    }

    @Test
    void pickBestTokenHonorsSuppressTokens() {
        float[] logits = new float[] {0.1f, 0.95f, 0.85f, 0.2f};
        float[] originalLogits = Arrays.copyOf(logits, logits.length);

        // Suppress token 1 (0.95f), so token 2 (0.85f) should be selected
        int best = LeaseBackedWhisperRunner.pickBestToken(logits, false, Set.of(1), Set.of());
        assertEquals(2, best);
        assertArrayEquals(originalLogits, logits);
    }

    @Test
    void pickBestTokenHonorsBeginSuppressTokensOnlyOnFirstGeneratedToken() {
        float[] logits = new float[] {0.1f, 0.95f, 0.85f, 0.2f};
        Set<Integer> beginSuppress = Set.of(1);

        // On first generated step, token 1 is suppressed -> token 2 is chosen
        int firstStep = LeaseBackedWhisperRunner.pickBestToken(logits, true, Set.of(), beginSuppress);
        assertEquals(2, firstStep);

        // On subsequent steps, token 1 is NOT suppressed -> token 1 is chosen
        int laterStep = LeaseBackedWhisperRunner.pickBestToken(logits, false, Set.of(), beginSuppress);
        assertEquals(1, laterStep);
    }

    @Test
    void computeLogMelDefaultProducesStandardWhisperShape() {
        float[] audio = new float[16000 * 2]; // 2 seconds of audio
        for (int i = 0; i < audio.length; i++) {
            audio[i] = (float) Math.sin(2.0 * Math.PI * 440.0 * i / 16000.0);
        }

        float[][] mel = LeaseBackedWhisperRunner.computeLogMel(audio);
        assertNotNull(mel);
        assertEquals(80, mel.length);
        assertEquals(3000, mel[0].length); // 30 sec * 16000 / 160 = 3000 frames

        float[][] melWithNullSpec = LeaseBackedWhisperRunner.computeLogMel(audio, null);
        assertEquals(mel.length, melWithNullSpec.length);
        assertEquals(mel[0].length, melWithNullSpec[0].length);
        assertEquals(mel[0][0], melWithNullSpec[0][0], 1e-6f);
    }

    @Test
    void computeLogMelHandlesSilenceWithoutNaNs() {
        float[] silence = new float[16000];
        float[][] mel = LeaseBackedWhisperRunner.computeLogMel(silence);
        assertNotNull(mel);
        for (int b = 0; b < mel.length; b++) {
            for (int f = 0; f < mel[0].length; f++) {
                assertFalse(Float.isNaN(mel[b][f]), "Mel output must not be NaN");
                assertFalse(Float.isInfinite(mel[b][f]), "Mel output must not be infinite");
            }
        }
    }

    @Test
    void computeLogMelRespectsCustomBundleSpecParameters() {
        OnnxAudioTranscriptionBundleSpec customSpec = new OnnxAudioTranscriptionBundleSpec(
                "decoder_model.onnx",
                VALID_SHA,
                1024L,
                "preprocessor_config.json",
                VALID_SHA,
                "config.json",
                VALID_SHA,
                16000,
                1,
                80,
                400,
                160,
                10, // 10 seconds duration limit
                448,
                true);

        float[] audio = new float[16000 * 5];
        float[][] mel = LeaseBackedWhisperRunner.computeLogMel(audio, customSpec);
        assertEquals(80, mel.length);
        assertEquals(1000, mel[0].length); // 10 sec * 16000 / 160 = 1000 frames
    }

    @Test
    void computeLogMelSupportsNonStandardFilterbanks() {
        OnnxAudioTranscriptionBundleSpec nonStandardSpec = new OnnxAudioTranscriptionBundleSpec(
                "decoder_model.onnx",
                VALID_SHA,
                1024L,
                "preprocessor_config.json",
                VALID_SHA,
                "config.json",
                VALID_SHA,
                16000,
                1,
                40, // 40 mel bins
                320, // 320 window length
                160,
                5, // 5 seconds duration limit
                448,
                true);

        float[] audio = new float[16000 * 2];
        float[][] mel = LeaseBackedWhisperRunner.computeLogMel(audio, nonStandardSpec);
        assertEquals(40, mel.length);
        assertEquals(500, mel[0].length); // 5 sec * 16000 / 160 = 500 frames
    }
}
