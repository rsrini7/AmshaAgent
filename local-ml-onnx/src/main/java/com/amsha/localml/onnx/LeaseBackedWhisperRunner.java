package com.amsha.localml.onnx;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * Implementation scaffolding for local offline Whisper transcription.
 * Executes both encoder and autoregressive greedy decoder passes strictly through
 * {@link OnnxSessionLease#run(Map, Instant)}.
 * <p>
 * <b>Note</b>: This class exists as unmanaged implementation scaffolding in {@code src/main/java};
 * it is NOT CDI-registered, configured, or exposed at runtime pending explicit human authorization.
 */
public final class LeaseBackedWhisperRunner {

    private static final int SAMPLE_RATE = 16000;
    private static final int N_FFT = 400;
    private static final int HOP_LENGTH = 160;
    private static final int NUM_MEL_BINS = 80;
    private static final int NUM_FRAMES = 3000;
    private static final int MAX_STEPS = 50;

    private final OnnxSessionLeaseManager leaseManager;
    private final VerifiedOnnxModelBundle encoderBundle;
    private final VerifiedOnnxModelBundle decoderBundle;
    private final Set<Integer> suppressTokens;
    private final Set<Integer> beginSuppressTokens;
    private final int eosTokenId;

    public LeaseBackedWhisperRunner(
            OnnxSessionLeaseManager leaseManager,
            VerifiedOnnxModelBundle encoderBundle,
            VerifiedOnnxModelBundle decoderBundle,
            Set<Integer> suppressTokens,
            Set<Integer> beginSuppressTokens,
            int eosTokenId) {
        this.leaseManager = Objects.requireNonNull(leaseManager, "leaseManager");
        this.encoderBundle = Objects.requireNonNull(encoderBundle, "encoderBundle");
        this.decoderBundle = Objects.requireNonNull(decoderBundle, "decoderBundle");
        this.suppressTokens = suppressTokens != null ? Set.copyOf(suppressTokens) : Set.of();
        this.beginSuppressTokens = beginSuppressTokens != null ? Set.copyOf(beginSuppressTokens) : Set.of();
        this.eosTokenId = eosTokenId;
    }

    /**
     * Executes the full speech-to-text pipeline for 16-kHz PCM audio within the given deadline.
     */
    public String transcribe(OrtEnvironment env, float[] audioPcm, Instant deadline) throws Exception {
        Objects.requireNonNull(env, "env");
        Objects.requireNonNull(audioPcm, "audioPcm");

        ensureBeforeDeadline(deadline);
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Transcription worker interrupted before start");
        }

        // 1. Pure Java Audio DSP
        float[][] mel = computeLogMel(audioPcm);
        float[][][] wrappedMel = wrapMelTensor(mel);

        // 2. Encoder pass via OnnxSessionLease
        float[][][] hidden;
        ensureBeforeDeadline(deadline);
        try (OnnxSessionLease encLease = leaseManager.acquire(encoderBundle, deadline);
                OnnxTensor inTensor = OnnxTensor.createTensor(env, wrappedMel);
                OrtSession.Result encResult = encLease.run(Map.of("input_features", inTensor), deadline)) {
            hidden = (float[][][]) encResult
                    .get("last_hidden_state")
                    .orElseThrow(() -> new IllegalStateException("Missing last_hidden_state in encoder output"))
                    .getValue();
        }

        // 3. Autoregressive greedy decoding loop via OnnxSessionLease
        ensureBeforeDeadline(deadline);
        try (OnnxSessionLease decLease = leaseManager.acquire(decoderBundle, deadline)) {
            HuggingFaceTokenizer tokenizer = decLease.tokenizer();
            List<Long> tokens = new ArrayList<>(List.of(50257L, 50362L));

            for (int step = 0; step < MAX_STEPS; step++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Transcription interrupted at decoder step " + step);
                }
                ensureBeforeDeadline(deadline);

                long[][] currentIds =
                        new long[][] {tokens.stream().mapToLong(Long::longValue).toArray()};
                try (OnnxTensor idsTensor = OnnxTensor.createTensor(env, currentIds);
                        OnnxTensor hidTensor = OnnxTensor.createTensor(env, hidden);
                        OrtSession.Result decResult = decLease.run(
                                Map.of("input_ids", idsTensor, "encoder_hidden_states", hidTensor), deadline)) {

                    int nextToken = pickNextToken(decResult, currentIds[0].length, tokens.size() == 2);
                    tokens.add((long) nextToken);
                    if (nextToken == eosTokenId) {
                        break;
                    }
                }
            }

            long[] tokenArray = tokens.stream().mapToLong(Long::longValue).toArray();
            return tokenizer.decode(tokenArray, true).trim();
        }
    }

    private int pickNextToken(OrtSession.Result decResult, int seqLen, boolean isFirstGenerated) throws OrtException {
        float[][][] logits = (float[][][]) decResult.get("logits").orElseThrow().getValue();
        float[] stepLogits = logits[0][seqLen - 1];

        for (int s : suppressTokens) {
            if (s >= 0 && s < stepLogits.length) stepLogits[s] = Float.NEGATIVE_INFINITY;
        }
        if (isFirstGenerated) {
            for (int s : beginSuppressTokens) {
                if (s >= 0 && s < stepLogits.length) stepLogits[s] = Float.NEGATIVE_INFINITY;
            }
        }

        int best = 0;
        float maxVal = stepLogits[0];
        for (int i = 1; i < stepLogits.length; i++) {
            if (stepLogits[i] > maxVal) {
                maxVal = stepLogits[i];
                best = i;
            }
        }
        return best;
    }

    private static void ensureBeforeDeadline(Instant deadline) throws TimeoutException {
        if (deadline != null && !Instant.now().isBefore(deadline)) {
            throw new TimeoutException("ONNX inference deadline exceeded");
        }
    }

    private static float[][][] wrapMelTensor(float[][] mel) {
        float[][][] wrapped = new float[1][NUM_MEL_BINS][NUM_FRAMES];
        for (int b = 0; b < NUM_MEL_BINS; b++) {
            System.arraycopy(mel[b], 0, wrapped[0][b], 0, NUM_FRAMES);
        }
        return wrapped;
    }

    /** Pure Java STFT and 80-channel log-Mel feature extractor. */
    public static float[][] computeLogMel(float[] audio) {
        int nSamples = 480000;
        float[] padded = new float[nSamples];
        int copyLen = Math.min(audio.length, nSamples);
        System.arraycopy(audio, 0, padded, 0, copyLen);

        int halfNfft = N_FFT / 2;
        float[] stftPadded = new float[nSamples + 2 * halfNfft];
        for (int i = 0; i < halfNfft; i++) {
            stftPadded[i] = padded[halfNfft - i];
        }
        System.arraycopy(padded, 0, stftPadded, halfNfft, nSamples);
        for (int i = 0; i < halfNfft; i++) {
            stftPadded[nSamples + halfNfft + i] = padded[nSamples - 2 - i];
        }

        float[] window = new float[N_FFT];
        for (int n = 0; n < N_FFT; n++) {
            window[n] = (float) (0.5 * (1.0 - Math.cos(2.0 * Math.PI * n / N_FFT)));
        }

        int numBins = halfNfft + 1;
        float[][] cosTable = new float[numBins][N_FFT];
        float[][] sinTable = new float[numBins][N_FFT];
        for (int k = 0; k < numBins; k++) {
            for (int n = 0; n < N_FFT; n++) {
                double angle = 2.0 * Math.PI * k * n / N_FFT;
                cosTable[k][n] = (float) Math.cos(angle);
                sinTable[k][n] = (float) Math.sin(angle);
            }
        }

        float[][] melFilters = computeSlaneyMelFilters(numBins, NUM_MEL_BINS, SAMPLE_RATE);
        float[][] logMel = new float[NUM_MEL_BINS][NUM_FRAMES];
        float[] frameBuffer = new float[N_FFT];
        float[] powerSpec = new float[numBins];
        float maxLog = -Float.MAX_VALUE;

        for (int f = 0; f < NUM_FRAMES; f++) {
            int offset = f * HOP_LENGTH;
            boolean isZero = true;
            for (int n = 0; n < N_FFT; n++) {
                float val = stftPadded[offset + n] * window[n];
                frameBuffer[n] = val;
                if (val != 0.0f) isZero = false;
            }

            if (isZero) {
                for (int b = 0; b < NUM_MEL_BINS; b++) logMel[b][f] = -10.0f;
                continue;
            }

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

            for (int b = 0; b < NUM_MEL_BINS; b++) {
                float[] filterRow = melFilters[b];
                float melEnergy = 0.0f;
                for (int k = 0; k < numBins; k++) melEnergy += filterRow[k] * powerSpec[k];
                if (melEnergy < 1e-10f) melEnergy = 1e-10f;
                float logVal = (float) Math.log10(melEnergy);
                logMel[b][f] = logVal;
                if (logVal > maxLog) maxLog = logVal;
            }
        }

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

    private static float[][] computeSlaneyMelFilters(int numBins, int numFilters, int sampleRate) {
        double minFreq = 0.0;
        double maxFreq = 8000.0;
        double minMel = hertzToMel(minFreq);
        double maxMel = hertzToMel(maxFreq);

        double[] melPoints = new double[numFilters + 2];
        double[] filterFreqs = new double[numFilters + 2];
        for (int i = 0; i < melPoints.length; i++) {
            melPoints[i] = minMel + i * (maxMel - minMel) / (numFilters + 1);
            filterFreqs[i] = melToHertz(melPoints[i]);
        }

        double[] fftFreqs = new double[numBins];
        for (int i = 0; i < numBins; i++) {
            fftFreqs[i] = i * (sampleRate / 2.0) / (numBins - 1);
        }

        float[][] filters = new float[numFilters][numBins];
        for (int i = 0; i < numFilters; i++) {
            double left = filterFreqs[i];
            double center = filterFreqs[i + 1];
            double right = filterFreqs[i + 2];
            double enorm = 2.0 / (right - left);

            for (int k = 0; k < numBins; k++) {
                double freq = fftFreqs[k];
                double weight = 0.0;
                if (freq >= left && freq <= center) {
                    weight = (freq - left) / (center - left);
                } else if (freq > center && freq <= right) {
                    weight = (right - freq) / (right - center);
                }
                filters[i][k] = (float) (weight * enorm);
            }
        }
        return filters;
    }

    private static double hertzToMel(double freq) {
        if (freq >= 1000.0) return 15.0 + Math.log(freq / 1000.0) * (27.0 / Math.log(6.4));
        return 3.0 * freq / 200.0;
    }

    private static double melToHertz(double mel) {
        if (mel >= 15.0) return 1000.0 * Math.exp((mel - 15.0) * (Math.log(6.4) / 27.0));
        return 200.0 * mel / 3.0;
    }
}
