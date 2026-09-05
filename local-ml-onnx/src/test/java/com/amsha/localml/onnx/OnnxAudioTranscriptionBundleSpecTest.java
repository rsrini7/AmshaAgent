package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

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
}
