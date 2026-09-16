package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OnnxAudioTranscriptionBundleSpecTest {

    private static final String VALID_SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void validSpecConstructsSuccessfully() {
        assertDoesNotThrow(() -> new OnnxAudioTranscriptionBundleSpec(
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
                true));
    }

    @Test
    void rejectsPathTraversalInDecoderFile() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new OnnxAudioTranscriptionBundleSpec(
                        "../decoder_model.onnx",
                        VALID_SHA,
                        1024L,
                        null,
                        null,
                        null,
                        null,
                        16000,
                        1,
                        80,
                        400,
                        160,
                        30,
                        448,
                        true));

        assertThrows(
                IllegalArgumentException.class,
                () -> new OnnxAudioTranscriptionBundleSpec(
                        "/absolute/decoder_model.onnx",
                        VALID_SHA,
                        1024L,
                        null,
                        null,
                        null,
                        null,
                        16000,
                        1,
                        80,
                        400,
                        160,
                        30,
                        448,
                        true));

        assertThrows(
                IllegalArgumentException.class,
                () -> new OnnxAudioTranscriptionBundleSpec(
                        "C:\\models\\decoder.onnx",
                        VALID_SHA,
                        1024L,
                        null,
                        null,
                        null,
                        null,
                        16000,
                        1,
                        80,
                        400,
                        160,
                        30,
                        448,
                        true));
    }

    @Test
    void rejectsInvalidSha256() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new OnnxAudioTranscriptionBundleSpec(
                        "decoder_model.onnx",
                        "not-a-sha256",
                        1024L,
                        null,
                        null,
                        null,
                        null,
                        16000,
                        1,
                        80,
                        400,
                        160,
                        30,
                        448,
                        true));
    }

    @Test
    void requiresSha256WhenConfigFileDeclared() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> new OnnxAudioTranscriptionBundleSpec(
                        "decoder_model.onnx",
                        VALID_SHA,
                        1024L,
                        null,
                        null,
                        "config.json",
                        null,
                        16000,
                        1,
                        80,
                        400,
                        160,
                        30,
                        448,
                        true));
        assertEquals("configSha256 is required when configFile is declared", e.getMessage());
    }

    @Test
    void rejectsNonPositiveParameters() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new OnnxAudioTranscriptionBundleSpec(
                        "decoder_model.onnx",
                        VALID_SHA,
                        0L,
                        null,
                        null,
                        null,
                        null,
                        16000,
                        1,
                        80,
                        400,
                        160,
                        30,
                        448,
                        true));

        assertThrows(
                IllegalArgumentException.class,
                () -> new OnnxAudioTranscriptionBundleSpec(
                        "decoder_model.onnx",
                        VALID_SHA,
                        1024L,
                        null,
                        null,
                        null,
                        null,
                        -1,
                        1,
                        80,
                        400,
                        160,
                        30,
                        448,
                        true));
    }

    @Test
    void constructorOverloadWorks() {
        OnnxAudioTranscriptionBundleSpec spec = new OnnxAudioTranscriptionBundleSpec(
                "decoder_model.onnx", VALID_SHA, 1024L, null, null, 16000, 1, 80, 400, 160, 30, 448, true);
        assertEquals("decoder_model.onnx", spec.decoderFile());
    }

    @Test
    void parseWhisperConfigHandlesNullOrMissing() {
        OnnxAudioTranscriptionBundleSpec spec = new OnnxAudioTranscriptionBundleSpec(
                "decoder_model.onnx", VALID_SHA, 1024L, null, null, 16000, 1, 80, 400, 160, 30, 448, true);
        var data = spec.parseWhisperConfig(null, null);
        assertEquals(50256, data.eosTokenId());
        assertNull(data.forcedPrefix());

        data = spec.parseWhisperConfig(Path.of("/non/existent/path.json"), new ObjectMapper());
        assertEquals(50256, data.eosTokenId());
    }

    @Test
    void parseWhisperConfigParsesValidJson(@TempDir Path tempDir) throws Exception {
        Path cfg = tempDir.resolve("config.json");
        String json = """
            {
              "eos_token_id": 50257,
              "decoder_start_token_id": 50258,
              "suppress_tokens": [1, 2, 3],
              "begin_suppress_tokens": [4, 5],
              "forced_decoder_ids": [[1, 50259], [2, 50359]]
            }
            """;
        Files.writeString(cfg, json);

        OnnxAudioTranscriptionBundleSpec spec = new OnnxAudioTranscriptionBundleSpec(
                "decoder_model.onnx", VALID_SHA, 1024L, null, null, 16000, 1, 80, 400, 160, 30, 448, true);
        var data = spec.parseWhisperConfig(cfg, new ObjectMapper());
        assertEquals(50257, data.eosTokenId());
        assertEquals(Set.of(1, 2, 3), data.suppressTokens());
        assertEquals(Set.of(4, 5), data.beginSuppressTokens());
        assertNotNull(data.forcedPrefix());
        assertEquals(List.of(50258L, 50259L, 50359L), data.forcedPrefix());
    }

    @Test
    void parseWhisperConfigThrowsOnCorruptJson(@TempDir Path tempDir) throws Exception {
        Path cfg = tempDir.resolve("corrupt.json");
        Files.writeString(cfg, "{ not json");
        OnnxAudioTranscriptionBundleSpec spec = new OnnxAudioTranscriptionBundleSpec(
                "decoder_model.onnx", VALID_SHA, 1024L, null, null, 16000, 1, 80, 400, 160, 30, 448, true);
        assertThrows(IllegalStateException.class, () -> spec.parseWhisperConfig(cfg, new ObjectMapper()));
    }
}
