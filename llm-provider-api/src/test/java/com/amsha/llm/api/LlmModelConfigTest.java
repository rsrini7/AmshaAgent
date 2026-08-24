package com.amsha.llm.api;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class LlmModelConfigTest {

    @Test
    void validatesValidConfig() {
        LlmModelConfig config = new LlmModelConfig(
                "openai",
                "gpt-4o",
                "https://api.openai.com/v1",
                Optional.of("secret"),
                Optional.of(4096),
                Optional.of(Duration.ofSeconds(30)),
                Optional.of(3),
                StructuredOutputMode.NATIVE,
                false);

        assertAll(
                () -> assertEquals("openai", config.provider()),
                () -> assertEquals("gpt-4o", config.modelName()),
                () -> assertEquals("https://api.openai.com/v1", config.baseUrl()),
                () -> assertEquals("secret", config.apiKey().orElseThrow()),
                () -> assertEquals(4096, config.maxTokens().orElseThrow()),
                () -> assertEquals(Duration.ofSeconds(30), config.timeout().orElseThrow()),
                () -> assertEquals(3, config.maxRetries().orElseThrow()),
                () -> assertEquals(StructuredOutputMode.NATIVE, config.structuredOutputMode()),
                () -> assertFalse(config.toolless()));
    }

    @Test
    void rejectsNullMandatoryFields() {
        assertThrows(
                NullPointerException.class,
                () -> new LlmModelConfig(
                        null,
                        "gpt-4o",
                        "http://localhost",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        StructuredOutputMode.NATIVE,
                        false));
        assertThrows(
                NullPointerException.class,
                () -> new LlmModelConfig(
                        "openai",
                        null,
                        "http://localhost",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        StructuredOutputMode.NATIVE,
                        false));
        assertThrows(
                NullPointerException.class,
                () -> new LlmModelConfig(
                        "openai",
                        "gpt-4o",
                        null,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        StructuredOutputMode.NATIVE,
                        false));
        assertThrows(
                NullPointerException.class,
                () -> new LlmModelConfig(
                        "openai",
                        "gpt-4o",
                        "http://localhost",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        null,
                        false));
    }
}
