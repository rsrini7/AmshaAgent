package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Evidence-only feasibility spike tests for local offline Whisper STT.
 * Proves:
 * 1. Exact Java 16-kHz PCM -> STFT -> 80-bin log-Mel feature extraction against Python librosa/HF golden reference.
 * 2. ONNX execution of quantized encoder and unmerged decoder models.
 * 3. DJL HuggingFace tokenizer compatibility with whisper tokenizer.json.
 * 4. Cancellation via OrtSession.RunOptions.setTerminate(true) and session recovery.
 * 5. Memory and latency measurements.
 */
class WhisperFeasibilitySpikeTest {

    private static final int SAMPLE_RATE = 16000;
    private static final int N_FFT = 400;
    private static final int HOP_LENGTH = 160;
    private static final int NUM_MEL_BINS = 80;
    private static final int NUM_FRAMES = 3000;
    private static final int N_SAMPLES = 480000;

    @Test
    void proveStftLogMelFeatureExtractionAgainstGoldenReference() throws Exception {
        Path evidenceDir = resolveEvidenceDir();
        Path audioFile = evidenceDir.resolve("golden_audio.raw");
        Path melFile = evidenceDir.resolve("golden_mel.bin");

        Assumptions.assumeTrue(Files.isRegularFile(audioFile), "golden_audio.raw must exist");
        Assumptions.assumeTrue(Files.isRegularFile(melFile), "golden_mel.bin must exist");

        byte[] audioBytes = Files.readAllBytes(audioFile);
        float[] audio = new float[audioBytes.length / 4];
        ByteBuffer.wrap(audioBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer()
                .get(audio);

        byte[] melBytes = Files.readAllBytes(melFile);
        float[] goldenFlat = new float[melBytes.length / 4];
        ByteBuffer.wrap(melBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(goldenFlat);
        assertEquals(NUM_MEL_BINS * NUM_FRAMES, goldenFlat.length);

        long startNs = System.nanoTime();
        float[][] javaMel = computeWhisperLogMel(audio);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

        assertEquals(NUM_MEL_BINS, javaMel.length);
        assertEquals(NUM_FRAMES, javaMel[0].length);

        float maxDiff = 0.0f;
        int maxDiffBin = -1;
        int maxDiffFrame = -1;

        for (int b = 0; b < NUM_MEL_BINS; b++) {
            for (int f = 0; f < NUM_FRAMES; f++) {
                float expected = goldenFlat[b * NUM_FRAMES + f];
                float actual = javaMel[b][f];
                float diff = Math.abs(expected - actual);
                if (diff > maxDiff) {
                    maxDiff = diff;
                    maxDiffBin = b;
                    maxDiffFrame = f;
                }
            }
        }

        System.out.printf(
                "[EVIDENCE] Java STFT/Mel computed in %d ms. Max diff vs golden reference: %.7e (at bin %d, frame %d)%n",
                elapsedMs, maxDiff, maxDiffBin, maxDiffFrame);

        // Golden equivalence threshold: machine precision (< 1e-4)
        assertTrue(maxDiff < 1e-4f, String.format("Java Mel spectrogram differed by %.7e (expected < 1e-4)", maxDiff));
    }

    @Test
    void proveOnnxEncoderDecoderAndTokenizerExecution() throws Exception {
        Path bundleDir = resolveBundleDir();
        Assumptions.assumeTrue(
                Files.isRegularFile(bundleDir.resolve("encoder_model.onnx")),
                "whisper-tiny-en-v1 bundle must be provisioned");

        OrtEnvironment env = OrtEnvironment.getEnvironment();
        Path encPath = bundleDir.resolve("encoder_model.onnx");
        Path decPath = bundleDir.resolve("decoder_model.onnx");
        Path tokPath = bundleDir.resolve("tokenizer.json");

        // 1. Verify Tokenizer loading and decoding
        try (HuggingFaceTokenizer tokenizer = new OnnxTokenizerFactory().load(tokPath)) {
            assertNotNull(tokenizer);
            String decoded = tokenizer.decode(new long[] {50258, 50363, 100, 200, 50257});
            assertNotNull(decoded);
            System.out.printf("[EVIDENCE] Tokenizer loaded successfully. Decoded sample: '%s'%n", decoded);
        }

        // 2. Load and run Encoder
        try (OrtSession encSession = env.createSession(encPath.toString(), new OrtSession.SessionOptions());
                OrtSession decSession = env.createSession(decPath.toString(), new OrtSession.SessionOptions())) {

            // Create test input tensor [1, 80, 3000]
            float[][][] testInput = new float[1][NUM_MEL_BINS][NUM_FRAMES];
            for (int b = 0; b < NUM_MEL_BINS; b++) {
                for (int f = 0; f < NUM_FRAMES; f++) {
                    testInput[0][b][f] = (f < 100) ? 0.5f : -0.5f;
                }
            }

            long encStart = System.nanoTime();
            float[][][] lastHiddenState;
            try (OnnxTensor inTensor = OnnxTensor.createTensor(env, testInput);
                    OrtSession.Result encResult = encSession.run(Map.of("input_features", inTensor))) {
                assertTrue(encResult.get("last_hidden_state").isPresent());
                lastHiddenState =
                        (float[][][]) encResult.get("last_hidden_state").get().getValue();
                assertEquals(1, lastHiddenState.length);
                assertEquals(1500, lastHiddenState[0].length);
                assertEquals(384, lastHiddenState[0][0].length);
            }
            long encMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - encStart);
            System.out.printf("[EVIDENCE] Encoder completed in %d ms. Output shape: [1, 1500, 384]%n", encMs);

            // 3. Load and run Decoder with prompt tokens [50258, 50363]
            long[][] promptTokens = new long[][] {{50258L, 50363L}};
            long decStart = System.nanoTime();
            try (OnnxTensor decInTokens = OnnxTensor.createTensor(env, promptTokens);
                    OnnxTensor decInHidden = OnnxTensor.createTensor(env, lastHiddenState);
                    OrtSession.Result decResult = decSession.run(Map.of(
                            "input_ids", decInTokens,
                            "encoder_hidden_states", decInHidden))) {

                assertTrue(decResult.get("logits").isPresent());
                float[][][] logits = (float[][][]) decResult.get("logits").get().getValue();
                assertEquals(1, logits.length);
                assertEquals(2, logits[0].length);
                assertEquals(51864, logits[0][0].length);

                // Argmax for next token
                float[] stepLogits = logits[0][1];
                int nextToken = 0;
                float maxLogit = stepLogits[0];
                for (int v = 1; v < stepLogits.length; v++) {
                    if (stepLogits[v] > maxLogit) {
                        maxLogit = stepLogits[v];
                        nextToken = v;
                    }
                }
                long decMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - decStart);
                System.out.printf(
                        "[EVIDENCE] Decoder first step completed in %d ms. Predicted next token: %d%n",
                        decMs, nextToken);
                assertTrue(nextToken >= 0 && nextToken < 51864);
            }
        }
    }

    @Test
    void proveCancellationWithRunOptionsTerminate() throws Exception {
        Path bundleDir = resolveBundleDir();
        Assumptions.assumeTrue(
                Files.isRegularFile(bundleDir.resolve("encoder_model.onnx")),
                "whisper-tiny-en-v1 bundle must be provisioned");

        OrtEnvironment env = OrtEnvironment.getEnvironment();
        Path encPath = bundleDir.resolve("encoder_model.onnx");

        try (OrtSession session = env.createSession(encPath.toString(), new OrtSession.SessionOptions())) {
            float[][][] testInput = new float[1][NUM_MEL_BINS][NUM_FRAMES];

            // Run normal once
            try (OnnxTensor inTensor = OnnxTensor.createTensor(env, testInput);
                    OrtSession.Result result = session.run(Map.of("input_features", inTensor))) {
                assertNotNull(result);
            }

            // Test cancellation: setTerminate(true) immediately before run
            OrtSession.RunOptions runOptions = new OrtSession.RunOptions();
            runOptions.setTerminate(true);

            try (OnnxTensor inTensor = OnnxTensor.createTensor(env, testInput)) {
                OrtException ex = assertThrows(
                        OrtException.class, () -> session.run(Map.of("input_features", inTensor), runOptions));
                System.out.printf(
                        "[EVIDENCE] Run successfully terminated via RunOptions.setTerminate(true). Error: %s%n",
                        ex.getMessage());
            } finally {
                runOptions.close();
            }

            // Verify session reusability after cancellation: subsequent run must succeed
            try (OnnxTensor inTensor = OnnxTensor.createTensor(env, testInput);
                    OrtSession.Result result = session.run(Map.of("input_features", inTensor))) {
                assertNotNull(result);
                System.out.println("[EVIDENCE] Session successfully reused for inference after cancellation.");
            }
        }
    }

    // --- Pure Java Whisper Audio Preprocessor ---

    static float[][] computeWhisperLogMel(float[] audio) {
        // 1. Right pad audio to 480,000 samples
        float[] rawPadded = new float[N_SAMPLES];
        int copyLen = Math.min(audio.length, N_SAMPLES);
        System.arraycopy(audio, 0, rawPadded, 0, copyLen);

        // 2. Reflect pad 200 samples on both sides -> 480,400 samples
        int pad = N_FFT / 2; // 200
        float[] stftPadded = new float[N_SAMPLES + N_FFT];
        // Left reflection: index 200 down to 1
        for (int i = 0; i < pad; i++) {
            stftPadded[i] = rawPadded[pad - i];
        }
        // Center
        System.arraycopy(rawPadded, 0, stftPadded, pad, N_SAMPLES);
        // Right reflection: index N_SAMPLES - 2 down
        for (int i = 0; i < pad; i++) {
            stftPadded[pad + N_SAMPLES + i] = rawPadded[N_SAMPLES - 2 - i];
        }

        // 3. Precompute Hann window (periodic, len 400)
        float[] window = new float[N_FFT];
        for (int n = 0; n < N_FFT; n++) {
            window[n] = (float) (0.5 * (1.0 - Math.cos(2.0 * Math.PI * n / N_FFT)));
        }

        // 4. Precompute DFT basis tables (201 bins x 400 points)
        int numBins = N_FFT / 2 + 1; // 201
        float[][] cosTable = new float[numBins][N_FFT];
        float[][] sinTable = new float[numBins][N_FFT];
        for (int k = 0; k < numBins; k++) {
            for (int n = 0; n < N_FFT; n++) {
                double angle = 2.0 * Math.PI * k * n / N_FFT;
                cosTable[k][n] = (float) Math.cos(angle);
                sinTable[k][n] = (float) Math.sin(angle);
            }
        }

        // 5. Precompute Slaney Mel filter bank (80 x 201)
        float[][] melFilters = computeSlaneyMelFilters(numBins, NUM_MEL_BINS, SAMPLE_RATE);

        // 6. Compute STFT power spectrum and Mel projection across frames
        // Number of frames: 1 + (480400 - 400) / 160 = 3001 frames
        int totalFrames = 1 + (stftPadded.length - N_FFT) / HOP_LENGTH;
        assertEquals(3001, totalFrames);

        float[][] logMel = new float[NUM_MEL_BINS][NUM_FRAMES];
        float[] frameBuffer = new float[N_FFT];
        float[] powerSpec = new float[numBins];
        float maxLog = -Float.MAX_VALUE;

        // Note: we process 3000 frames (dropping the 3001st frame)
        for (int f = 0; f < NUM_FRAMES; f++) {
            int offset = f * HOP_LENGTH;

            // Check if frame is entirely zero (optimization for padded tail)
            boolean isZero = true;
            for (int n = 0; n < N_FFT; n++) {
                float val = stftPadded[offset + n] * window[n];
                frameBuffer[n] = val;
                if (val != 0.0f) isZero = false;
            }

            if (isZero) {
                for (int b = 0; b < NUM_MEL_BINS; b++) {
                    logMel[b][f] = -10.0f; // log10(1e-10)
                }
                continue;
            }

            // Compute DFT power
            for (int k = 0; k < numBins; k++) {
                float real = 0.0f;
                float imag = 0.0f;
                float[] cosRow = cosTable[k];
                float[] sinRow = sinTable[k];
                for (int n = 0; n < N_FFT; n++) {
                    float s = frameBuffer[n];
                    real += s * cosRow[n];
                    imag -= s * sinRow[n];
                }
                powerSpec[k] = real * real + imag * imag;
            }

            // Project to Mel bins
            for (int b = 0; b < NUM_MEL_BINS; b++) {
                float[] filterRow = melFilters[b];
                float melEnergy = 0.0f;
                for (int k = 0; k < numBins; k++) {
                    melEnergy += filterRow[k] * powerSpec[k];
                }
                if (melEnergy < 1e-10f) melEnergy = 1e-10f;
                float logVal = (float) Math.log10(melEnergy);
                logMel[b][f] = logVal;
                if (logVal > maxLog) maxLog = logVal;
            }
        }

        // 7. Normalization: max(logMel, maxLog - 8.0), then (val + 4.0) / 4.0
        float floor = maxLog - 8.0f;
        for (int b = 0; b < NUM_MEL_BINS; b++) {
            for (int f = 0; f < NUM_FRAMES; f++) {
                float val = logMel[b][f];
                if (val < floor) val = floor;
                logMel[b][f] = (val + 4.0f) / 4.0f;
            }
        }

        return logMel;
    }

    static float[][] computeSlaneyMelFilters(int numBins, int numFilters, int sampleRate) {
        double minFreq = 0.0;
        double maxFreq = 8000.0;

        double minMel = hertzToMelSlaney(minFreq);
        double maxMel = hertzToMelSlaney(maxFreq);

        double[] melPoints = new double[numFilters + 2];
        double[] filterFreqs = new double[numFilters + 2];
        for (int i = 0; i < melPoints.length; i++) {
            melPoints[i] = minMel + i * (maxMel - minMel) / (numFilters + 1);
            filterFreqs[i] = melToHertzSlaney(melPoints[i]);
        }

        double[] fftFreqs = new double[numBins];
        for (int i = 0; i < numBins; i++) {
            fftFreqs[i] = i * (sampleRate / 2.0) / (numBins - 1);
        }

        float[][] filters = new float[numFilters][numBins];
        for (int i = 0; i < numFilters; i++) {
            double leftFreq = filterFreqs[i];
            double centerFreq = filterFreqs[i + 1];
            double rightFreq = filterFreqs[i + 2];

            double enorm = 2.0 / (rightFreq - leftFreq);

            for (int k = 0; k < numBins; k++) {
                double freq = fftFreqs[k];
                double weight = 0.0;
                if (freq >= leftFreq && freq <= centerFreq) {
                    weight = (freq - leftFreq) / (centerFreq - leftFreq);
                } else if (freq > centerFreq && freq <= rightFreq) {
                    weight = (rightFreq - freq) / (rightFreq - centerFreq);
                }
                filters[i][k] = (float) (weight * enorm);
            }
        }
        return filters;
    }

    static double hertzToMelSlaney(double freq) {
        if (freq >= 1000.0) {
            return 15.0 + Math.log(freq / 1000.0) * (27.0 / Math.log(6.4));
        }
        return 3.0 * freq / 200.0;
    }

    static double melToHertzSlaney(double mel) {
        if (mel >= 15.0) {
            return 1000.0 * Math.exp((mel - 15.0) * (Math.log(6.4) / 27.0));
        }
        return 200.0 * mel / 3.0;
    }

    private static Path resolveEvidenceDir() {
        return (Files.isDirectory(Path.of(".scratch/amsha-visual-cockpit/evidence"))
                        ? Path.of(".scratch/amsha-visual-cockpit/evidence")
                        : Path.of("../.scratch/amsha-visual-cockpit/evidence"))
                .toAbsolutePath();
    }

    private static Path resolveBundleDir() {
        return (Files.isDirectory(Path.of("data/models/whisper-tiny-en-v1"))
                        ? Path.of("data/models/whisper-tiny-en-v1")
                        : Path.of("../data/models/whisper-tiny-en-v1"))
                .toAbsolutePath();
    }
}
