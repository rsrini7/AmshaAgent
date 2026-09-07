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
 * Execution runner for local offline Whisper speech-to-text transcription.
 * Executes both encoder and autoregressive greedy decoder passes strictly through
 * {@link OnnxSessionLease#run(Map, Instant)}.
 */
public final class LeaseBackedWhisperRunner {

    public static final long SOT_TOKEN_ID = 50257L;
    public static final long ENGLISH_TOKEN_ID = 50258L;
    public static final long TRANSCRIBE_TOKEN_ID = 50358L;
    public static final long NO_TIMESTAMPS_TOKEN_ID = 50362L;

    private static final int SAMPLE_RATE = 16000;
    private static final int N_FFT = 400;
    private static final int HOP_LENGTH = 160;
    private static final int NUM_MEL_BINS = 80;
    private static final int NUM_BINS = (N_FFT / 2) + 1;

    private static final float[] WINDOW = precomputeWindow(N_FFT);
    private static final float[][] COS_TABLE = precomputeTrigTable(NUM_BINS, N_FFT, true);
    private static final float[][] SIN_TABLE = precomputeTrigTable(NUM_BINS, N_FFT, false);
    private static final float[][] MEL_FILTERS = computeSlaneyMelFilters(NUM_BINS, NUM_MEL_BINS, SAMPLE_RATE);

    private final OnnxSessionLeaseManager leaseManager;
    private final VerifiedOnnxModelBundle encoderBundle;
    private final VerifiedOnnxModelBundle decoderBundle;
    private final OnnxAudioTranscriptionBundleSpec spec;
    private final List<Long> initialPrefixTokens;
    private final Set<Integer> suppressTokens;
    private final Set<Integer> beginSuppressTokens;
    private final int eosTokenId;

    public LeaseBackedWhisperRunner(
            OnnxSessionLeaseManager leaseManager,
            VerifiedOnnxModelBundle encoderBundle,
            VerifiedOnnxModelBundle decoderBundle,
            OnnxAudioTranscriptionBundleSpec spec,
            List<Long> initialPrefixTokens,
            Set<Integer> suppressTokens,
            Set<Integer> beginSuppressTokens,
            int eosTokenId) {
        this.leaseManager = Objects.requireNonNull(leaseManager, "leaseManager");
        this.encoderBundle = Objects.requireNonNull(encoderBundle, "encoderBundle");
        this.decoderBundle = Objects.requireNonNull(decoderBundle, "decoderBundle");
        this.spec = spec;
        this.initialPrefixTokens =
                initialPrefixTokens != null && !initialPrefixTokens.isEmpty() ? List.copyOf(initialPrefixTokens) : null;
        this.suppressTokens = suppressTokens != null ? Set.copyOf(suppressTokens) : Set.of();
        this.beginSuppressTokens = beginSuppressTokens != null ? Set.copyOf(beginSuppressTokens) : Set.of();
        this.eosTokenId = eosTokenId;
    }

    public LeaseBackedWhisperRunner(
            OnnxSessionLeaseManager leaseManager,
            VerifiedOnnxModelBundle encoderBundle,
            VerifiedOnnxModelBundle decoderBundle,
            Set<Integer> suppressTokens,
            Set<Integer> beginSuppressTokens,
            int eosTokenId) {
        this(leaseManager, encoderBundle, decoderBundle, null, null, suppressTokens, beginSuppressTokens, eosTokenId);
    }

    public static List<Long> defaultPrefix(boolean englishOnly) {
        return englishOnly
                ? List.of(SOT_TOKEN_ID, NO_TIMESTAMPS_TOKEN_ID)
                : List.of(SOT_TOKEN_ID, ENGLISH_TOKEN_ID, TRANSCRIBE_TOKEN_ID, NO_TIMESTAMPS_TOKEN_ID);
    }

    public OnnxAudioTranscriptionBundleSpec spec() {
        return spec;
    }

    public List<Long> initialPrefixTokens() {
        return initialPrefixTokens != null ? initialPrefixTokens : defaultPrefix(true);
    }

    /**
     * Executes the full speech-to-text pipeline for 16-kHz PCM audio within the given deadline.
     */
    public String transcribe(OrtEnvironment env, float[] audioPcm, Instant deadline) throws Exception {
        return transcribe(env, audioPcm, deadline, null);
    }

    /**
     * Executes the full speech-to-text pipeline for 16-kHz PCM audio with language hint and deadline.
     */
    public String transcribe(OrtEnvironment env, float[] audioPcm, Instant deadline, String languageHint)
            throws Exception {
        Objects.requireNonNull(env, "env");
        Objects.requireNonNull(audioPcm, "audioPcm");

        ensureBeforeDeadline(deadline);
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Transcription worker interrupted before start");
        }

        // 1. Pure Java Audio DSP driven by bundle spec
        float[][] mel = computeLogMel(audioPcm, spec);
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
            List<Long> tokens = new ArrayList<>(resolvePrefixTokens(languageHint));
            int maxSequenceLength = spec != null ? spec.maxSequenceLength() : 448;
            int maxSteps = Math.max(1, maxSequenceLength - tokens.size());

            for (int step = 0; step < maxSteps; step++) {
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

                    int nextToken = pickNextToken(decResult, currentIds[0].length, step == 0);
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

    private List<Long> resolvePrefixTokens(String languageHint) {
        if (initialPrefixTokens != null && !initialPrefixTokens.isEmpty()) {
            return initialPrefixTokens;
        }
        boolean englishOnly =
                languageHint == null || "en".equalsIgnoreCase(languageHint) || "english".equalsIgnoreCase(languageHint);
        return defaultPrefix(englishOnly);
    }

    private int pickNextToken(OrtSession.Result decResult, int seqLen, boolean isFirstGenerated) throws OrtException {
        float[][][] logits = (float[][][]) decResult.get("logits").orElseThrow().getValue();
        int stepIdx = Math.max(0, Math.min(seqLen - 1, logits[0].length - 1));
        return pickBestToken(logits[0][stepIdx], isFirstGenerated, suppressTokens, beginSuppressTokens);
    }

    static int pickBestToken(
            float[] stepLogits,
            boolean isFirstGenerated,
            Set<Integer> suppressTokens,
            Set<Integer> beginSuppressTokens) {
        int best = 0;
        float maxVal = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < stepLogits.length; i++) {
            if (suppressTokens != null && suppressTokens.contains(i)) {
                continue;
            }
            if (isFirstGenerated && beginSuppressTokens != null && beginSuppressTokens.contains(i)) {
                continue;
            }
            float val = stepLogits[i];
            if (val > maxVal) {
                maxVal = val;
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
        int bins = mel.length;
        int frames = mel[0].length;
        float[][][] wrapped = new float[1][bins][frames];
        for (int b = 0; b < bins; b++) {
            System.arraycopy(mel[b], 0, wrapped[0][b], 0, frames);
        }
        return wrapped;
    }

    /** Pure Java STFT and log-Mel feature extractor using default parameters. */
    public static float[][] computeLogMel(float[] audio) {
        return computeLogMel(audio, null);
    }

    /** Pure Java STFT and log-Mel feature extractor parameterized by {@link OnnxAudioTranscriptionBundleSpec}. */
    public static float[][] computeLogMel(float[] audio, OnnxAudioTranscriptionBundleSpec spec) {
        int nFft = spec != null ? spec.windowLength() : N_FFT;
        int hopLength = spec != null ? spec.hopLength() : HOP_LENGTH;
        int numMelBins = spec != null ? spec.numMelBins() : NUM_MEL_BINS;
        int sampleRate = spec != null ? spec.sampleRate() : SAMPLE_RATE;
        int maxDurationSec = spec != null ? spec.maxAudioDurationSeconds() : 30;
        int nSamples = maxDurationSec * sampleRate;
        int numFrames = nSamples / hopLength;
        int numBins = (nFft / 2) + 1;

        boolean isStandard =
                (nFft == N_FFT && hopLength == HOP_LENGTH && numMelBins == NUM_MEL_BINS && sampleRate == SAMPLE_RATE);
        float[] window = isStandard ? WINDOW : precomputeWindow(nFft);
        float[][] cosTable = isStandard ? COS_TABLE : precomputeTrigTable(numBins, nFft, true);
        float[][] sinTable = isStandard ? SIN_TABLE : precomputeTrigTable(numBins, nFft, false);
        float[][] melFilters = isStandard ? MEL_FILTERS : computeSlaneyMelFilters(numBins, numMelBins, sampleRate);

        float[] stftPadded = padAudioForStft(audio, nSamples, nFft);

        float[][] logMel = new float[numMelBins][numFrames];
        float[] frameBuffer = new float[nFft];
        float[] powerSpec = new float[numBins];
        float maxLog = -Float.MAX_VALUE;

        for (int f = 0; f < numFrames; f++) {
            int offset = f * hopLength;
            boolean isZero = true;
            for (int n = 0; n < nFft; n++) {
                float val = stftPadded[offset + n] * window[n];
                frameBuffer[n] = val;
                if (val != 0.0f) isZero = false;
            }

            if (isZero) {
                for (int b = 0; b < numMelBins; b++) logMel[b][f] = -10.0f;
                continue;
            }

            for (int k = 0; k < numBins; k++) {
                float real = 0.0f;
                float imag = 0.0f;
                float[] cosRow = cosTable[k];
                float[] sinRow = sinTable[k];
                for (int n = 0; n < nFft; n++) {
                    float s = frameBuffer[n];
                    real += s * cosRow[n];
                    imag -= s * sinRow[n];
                }
                powerSpec[k] = real * real + imag * imag;
            }

            for (int b = 0; b < numMelBins; b++) {
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
        for (int b = 0; b < numMelBins; b++) {
            for (int f = 0; f < numFrames; f++) {
                float val = logMel[b][f];
                if (val < floor) val = floor;
                logMel[b][f] = (val + 4.0f) / 4.0f;
            }
        }
        return logMel;
    }

    private static float[] precomputeWindow(int nFft) {
        float[] window = new float[nFft];
        for (int n = 0; n < nFft; n++) {
            window[n] = (float) (0.5 * (1.0 - Math.cos(2.0 * Math.PI * n / nFft)));
        }
        return window;
    }

    private static float[][] precomputeTrigTable(int numBins, int nFft, boolean isCos) {
        float[][] table = new float[numBins][nFft];
        for (int k = 0; k < numBins; k++) {
            for (int n = 0; n < nFft; n++) {
                double angle = 2.0 * Math.PI * k * n / nFft;
                table[k][n] = (float) (isCos ? Math.cos(angle) : Math.sin(angle));
            }
        }
        return table;
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

    private static float[] padAudioForStft(float[] audio, int nSamples, int nFft) {
        float[] padded = new float[nSamples];
        int copyLen = Math.min(audio.length, nSamples);
        System.arraycopy(audio, 0, padded, 0, copyLen);

        int halfNfft = nFft / 2;
        float[] stftPadded = new float[nSamples + 2 * halfNfft];
        for (int i = 0; i < halfNfft; i++) {
            stftPadded[i] = padded[halfNfft - i];
        }
        System.arraycopy(padded, 0, stftPadded, halfNfft, nSamples);
        for (int i = 0; i < halfNfft; i++) {
            stftPadded[nSamples + halfNfft + i] = padded[nSamples - 2 - i];
        }
        return stftPadded;
    }
}
