package com.amsha.llm.api;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class AgentLlmConfigTest {

    @Test
    void testConfigGetters() throws Exception {
        AgentLlmConfig config = new AgentLlmConfig();
        set(config, "maxTokens", 2048);
        set(config, "timeout", Duration.ofSeconds(60));
        set(config, "maxRetries", 3);
        set(config, "temperature", 0.7);
        set(config, "logHttp", true);
        set(config, "structuredOutputMode", StructuredOutputMode.LENIENT);

        assertAll(
                () -> assertEquals(2048, config.maxTokens()),
                () -> assertEquals(Duration.ofSeconds(60), config.timeout()),
                () -> assertEquals(3, config.maxRetries()),
                () -> assertEquals(0.7, config.temperature()),
                () -> assertTrue(config.logHttp()),
                () -> assertEquals(StructuredOutputMode.LENIENT, config.structuredOutputMode()));
    }

    private static void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
