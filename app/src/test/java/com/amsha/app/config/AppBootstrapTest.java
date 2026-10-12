package com.amsha.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.amsha.common.config.ConfigKeys;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppBootstrapTest {

    @AfterEach
    void cleanup() {
        System.clearProperty("KEY_A");
        System.clearProperty("KEY_B");
        System.clearProperty("KEY_C");
        System.clearProperty("KEY_D");
        System.clearProperty("KEY_E");
        System.clearProperty(ConfigKeys.LLM_PROVIDER);
        System.clearProperty("AMSHA_LLM_CONTEXT_WINDOW");
        System.clearProperty("AMSHA_LLM_COMPACT_PROMPT_ENABLED");
        System.clearProperty("AMSHA_LLM_STRUCTURED_OUTPUT_MODE");
        System.clearProperty("AMSHA_INTENT_CLASSIFIER_STRUCTURED_OUTPUT_MODE");
        System.clearProperty("AMSHA_COMPACTION_STRUCTURED_OUTPUT_MODE");
        System.clearProperty("AMSHA_INFINITE_LOOP_LLM_PROVIDERS");
        System.clearProperty("AMSHA_INFINITE_LOOP_COMPACTION_PROVIDERS");
        System.clearProperty("AMSHA_INFINITE_LOOP_CLASSIFIER_PROVIDERS");
        System.clearProperty("AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS");
    }

    @Test
    void testLoadDotEnv_setsSystemProperties(@TempDir Path tempDir) throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.write(
                envFile,
                List.of(
                        "KEY_A=hello",
                        "KEY_B=world",
                        "# comment line",
                        "KEY_C=value with spaces",
                        "KEY_D=value #inline comment",
                        "KEY_E=\"quoted#value\""));

        EnvConfigInitializer.loadDotEnv(tempDir);

        assertEquals("hello", System.getProperty("KEY_A"));
        assertEquals("world", System.getProperty("KEY_B"));
        assertEquals("value with spaces", System.getProperty("KEY_C"));
        assertEquals("value", System.getProperty("KEY_D"));
        assertEquals("\"quoted#value\"", System.getProperty("KEY_E"));
    }

    @Test
    void testLoadDotEnv_appliesWhenNoOsEnvVar(@TempDir Path tempDir) throws IOException {
        // In GraalVM native-image, loadDotEnv checks System.getenv() (OS env vars),
        // not System.getProperty(). When no OS env var exists, .env value is applied.
        Path envFile = tempDir.resolve(".env");
        Files.write(envFile, List.of("KEY_A=from_dotenv"));

        EnvConfigInitializer.loadDotEnv(tempDir);

        assertEquals("from_dotenv", System.getProperty("KEY_A"));
    }

    @Test
    void testLoadDotEnv_missingFile_doesNotThrow() {
        EnvConfigInitializer.loadDotEnv(Path.of("/nonexistent/path/at/all/ever"));
    }

    @Test
    void testValidateLlmConfig_doesNotOverrideSystemProperties(@TempDir Path tempDir) throws Exception {
        System.setProperty(ConfigKeys.LLM_PROVIDER, "cerebras");
        System.setProperty("AMSHA_LLM_CONTEXT_WINDOW", "8192");

        Path envFile = tempDir.resolve(".env");
        Files.write(envFile, List.of("AMSHA_LLM_PROVIDER=cerebras", "AMSHA_LLM_CONTEXT_WINDOW=8192"));

        invokeValidateLlmConfig(tempDir);

        // Validator should NOT set any System properties - it only warns
        assertEquals("8192", System.getProperty("AMSHA_LLM_CONTEXT_WINDOW"));
    }

    @Test
    void testValidateLlmConfig_doesNotSetDefaultsForMissingValues(@TempDir Path tempDir) throws Exception {
        System.setProperty(ConfigKeys.LLM_PROVIDER, "groq");
        // Ensure these are blank so the validator would warn
        System.setProperty("AMSHA_LLM_COMPACT_PROMPT_ENABLED", "");
        System.setProperty("AMSHA_LLM_STRUCTURED_OUTPUT_MODE", "");

        Path envFile = tempDir.resolve(".env");
        Files.write(envFile, List.of("AMSHA_LLM_PROVIDER=groq"));

        invokeValidateLlmConfig(tempDir);

        // Validator should NOT set defaults - it only warns
        assertEquals("", System.getProperty("AMSHA_LLM_COMPACT_PROMPT_ENABLED"));
        assertEquals("", System.getProperty("AMSHA_LLM_STRUCTURED_OUTPUT_MODE"));
    }

    private static void invokeValidateLlmConfig(Path binaryDir) throws Exception {
        Method method = LlmConfigValidator.class.getDeclaredMethod("validateLlmConfig", Path.class);
        method.setAccessible(true);
        try {
            method.invoke(null, binaryDir);
        } finally {
            method.setAccessible(false);
        }
    }
}
