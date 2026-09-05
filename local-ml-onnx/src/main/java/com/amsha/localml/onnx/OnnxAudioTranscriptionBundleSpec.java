package com.amsha.localml.onnx;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Immutable graph, acoustic feature extraction, and autoregressive decoding contract
 * for a local ONNX audio transcription (Whisper) bundle.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OnnxAudioTranscriptionBundleSpec(
        String decoderFile,
        String decoderSha256,
        long decoderBytes,
        String preprocessorConfigFile,
        String preprocessorConfigSha256,
        String configFile,
        String configSha256,
        int sampleRate,
        int numChannels,
        int numMelBins,
        int windowLength,
        int hopLength,
        int maxAudioDurationSeconds,
        int maxSequenceLength,
        boolean suppressBlank) {

    public OnnxAudioTranscriptionBundleSpec {
        decoderFile = OnnxModelBundleManifest.requireRelativeFile(decoderFile, "decoderFile");
        decoderSha256 = OnnxModelBundleManifest.requireSha256(decoderSha256, "decoderSha256");
        preprocessorConfigFile = preprocessorConfigFile == null || preprocessorConfigFile.isBlank()
                ? null
                : OnnxModelBundleManifest.requireRelativeFile(preprocessorConfigFile, "preprocessorConfigFile");
        preprocessorConfigSha256 = preprocessorConfigSha256 == null || preprocessorConfigSha256.isBlank()
                ? null
                : OnnxModelBundleManifest.requireSha256(preprocessorConfigSha256, "preprocessorConfigSha256");
        configFile = configFile == null || configFile.isBlank()
                ? null
                : OnnxModelBundleManifest.requireRelativeFile(configFile, "configFile");
        configSha256 = configSha256 == null || configSha256.isBlank()
                ? null
                : OnnxModelBundleManifest.requireSha256(configSha256, "configSha256");
        if (preprocessorConfigFile != null && preprocessorConfigSha256 == null) {
            throw new IllegalArgumentException(
                    "preprocessorConfigSha256 is required when preprocessorConfigFile is declared");
        }
        if (configFile != null && configSha256 == null) {
            throw new IllegalArgumentException("configSha256 is required when configFile is declared");
        }
        if (decoderBytes <= 0) {
            throw new IllegalArgumentException("decoderBytes must be positive");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive");
        }
        if (numChannels <= 0) {
            throw new IllegalArgumentException("numChannels must be positive");
        }
        if (numMelBins <= 0) {
            throw new IllegalArgumentException("numMelBins must be positive");
        }
        if (windowLength <= 0) {
            throw new IllegalArgumentException("windowLength must be positive");
        }
        if (hopLength <= 0) {
            throw new IllegalArgumentException("hopLength must be positive");
        }
        if (maxAudioDurationSeconds <= 0) {
            throw new IllegalArgumentException("maxAudioDurationSeconds must be positive");
        }
        if (maxSequenceLength <= 0) {
            throw new IllegalArgumentException("maxSequenceLength must be positive");
        }
    }

    public OnnxAudioTranscriptionBundleSpec(
            String decoderFile,
            String decoderSha256,
            long decoderBytes,
            String preprocessorConfigFile,
            String configFile,
            int sampleRate,
            int numChannels,
            int numMelBins,
            int windowLength,
            int hopLength,
            int maxAudioDurationSeconds,
            int maxSequenceLength,
            boolean suppressBlank) {
        this(
                decoderFile,
                decoderSha256,
                decoderBytes,
                preprocessorConfigFile,
                null,
                configFile,
                null,
                sampleRate,
                numChannels,
                numMelBins,
                windowLength,
                hopLength,
                maxAudioDurationSeconds,
                maxSequenceLength,
                suppressBlank);
    }

    public VerifiedOnnxModelBundle createDecoderBundle(VerifiedOnnxModelBundle encoderBundle, Path decoderModel) {
        OnnxModelBundleManifest encMan = encoderBundle.manifest();
        OnnxModelBundleManifest.OnnxModelBundleSource src = encMan.source();
        OnnxModelBundleManifest.OnnxModelBundleSource decSource = new OnnxModelBundleManifest.OnnxModelBundleSource(
                src.repository(),
                src.decoderRevision() != null ? src.decoderRevision() : src.revision(),
                src.decoderPath(),
                src.tokenizerPath(),
                null,
                null,
                src.license());
        long decBytes = decoderBytes + encMan.tokenizerBytes();
        OnnxModelBundleManifest decManifest = new OnnxModelBundleManifest(
                encMan.bundleId() + "-decoder",
                encMan.revision(),
                decoderFile,
                decoderSha256,
                decoderBytes,
                encMan.tokenizerFile(),
                encMan.tokenizerSha256(),
                encMan.tokenizerBytes(),
                Math.max(decBytes * 2, decBytes),
                null,
                null,
                null,
                null,
                decSource);
        return new VerifiedOnnxModelBundle(decManifest, decoderModel, encoderBundle.tokenizerPath());
    }

    public record WhisperConfigData(
            List<Long> forcedPrefix, Set<Integer> suppressTokens, Set<Integer> beginSuppressTokens, int eosTokenId) {}

    public WhisperConfigData parseWhisperConfig(Path configFile, ObjectMapper objectMapper) {
        if (configFile == null || !Files.isRegularFile(configFile, LinkOption.NOFOLLOW_LINKS) || objectMapper == null) {
            return new WhisperConfigData(null, Set.of(), Set.of(), 50256);
        }
        try (InputStream in = Files.newInputStream(configFile)) {
            JsonNode cfg = objectMapper.readTree(in);
            Set<Integer> suppressTokens = parseTokenSet(cfg.get("suppress_tokens"));
            Set<Integer> beginSuppressTokens = parseTokenSet(cfg.get("begin_suppress_tokens"));
            int eosTokenId = cfg.has("eos_token_id") ? cfg.get("eos_token_id").asInt(50256) : 50256;
            int startId = cfg.path("decoder_start_token_id").asInt((int) LeaseBackedWhisperRunner.SOT_TOKEN_ID);
            List<Long> forcedPrefix = parseForcedPrefix(cfg.get("forced_decoder_ids"), startId);
            return new WhisperConfigData(forcedPrefix, suppressTokens, beginSuppressTokens, eosTokenId);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse Whisper model configuration: " + configFile, e);
        }
    }

    private static List<Long> parseForcedPrefix(JsonNode node, int startTokenId) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return null;
        }
        List<int[]> pairs = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isArray() && item.size() >= 2) {
                pairs.add(new int[] {item.get(0).asInt(), item.get(1).asInt()});
            }
        }
        if (pairs.isEmpty()) {
            return null;
        }
        pairs.sort(Comparator.comparingInt(a -> a[0]));
        List<Long> prefix = new ArrayList<>();
        prefix.add((long) startTokenId);
        for (int[] p : pairs) {
            prefix.add((long) p[1]);
        }
        return List.copyOf(prefix);
    }

    private static Set<Integer> parseTokenSet(JsonNode node) {
        if (node == null || !node.isArray()) return Set.of();
        Set<Integer> set = new HashSet<>();
        for (JsonNode item : node) {
            if (item.isNumber()) set.add(item.asInt());
        }
        return Set.copyOf(set);
    }
}
