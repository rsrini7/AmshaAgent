package com.arcclaw.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        System.clearProperty("ARCCLAW_LLM_PROVIDER");
        System.clearProperty("ARCCLAW_LLM_CONTEXT_WINDOW");
        System.clearProperty("arcclaw.llm.context-window");
        System.clearProperty("ARCCLAW_LLM_COMPACT_PROMPT_ENABLED");
        System.clearProperty("arcclaw.llm.compact-prompt-enabled");
        System.clearProperty("ARCCLAW_LLM_STRUCTURED_OUTPUT_MODE");
        System.clearProperty("arcclaw.llm.structured-output-mode");
        System.clearProperty("ARCCLAW_INTENT_CLASSIFIER_STRUCTURED_OUTPUT_MODE");
        System.clearProperty("arcclaw.routing.intent-classifier.structured-output-mode");
        System.clearProperty("ARCCLAW_COMPACTION_STRUCTURED_OUTPUT_MODE");
        System.clearProperty("arcclaw.memory.compaction.structured-output-mode");
        System.clearProperty("ARCCLAW_COMPACTION_PROVIDER");
        System.clearProperty("ARCCLAW_INTENT_CLASSIFIER_PROVIDER");
    }

    @Test
    void testLoadDotEnv_setsSystemProperties(@TempDir Path tempDir) throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.write(envFile, List.of(
            "KEY_A=hello",
            "KEY_B=world",
            "# comment line",
            "KEY_C=value with spaces",
            "KEY_D=value #inline comment",
            "KEY_E=\"quoted#value\""
        ));

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
    void testValidateAndAutoFixLlmConfig_publishesEffectiveContextWindowToDottedKey(@TempDir Path tempDir) throws Exception {
        System.setProperty("ARCCLAW_LLM_PROVIDER", "cerebras");
        System.setProperty("ARCCLAW_LLM_CONTEXT_WINDOW", "8192");

        Path envFile = tempDir.resolve(".env");
        Files.write(envFile, List.of(
            "ARCCLAW_LLM_PROVIDER=cerebras",
            "ARCCLAW_LLM_CONTEXT_WINDOW=8192"
        ));

        invokeValidateAndAutoFixLlmConfig(tempDir);

        assertEquals("8192", System.getProperty("ARCCLAW_LLM_CONTEXT_WINDOW"));
        assertEquals("8192", System.getProperty("arcclaw.llm.context-window"));
    }

    @Test
    void testValidateAndAutoFixLlmConfig_defaultsCompactPromptFlagToFalse(@TempDir Path tempDir) throws Exception {
        System.setProperty("ARCCLAW_LLM_PROVIDER", "groq");
        // Ensure environment variable doesn't override the test by setting property to blank
        System.setProperty("ARCCLAW_LLM_COMPACT_PROMPT_ENABLED", "");

        Path envFile = tempDir.resolve(".env");
        Files.write(envFile, List.of("ARCCLAW_LLM_PROVIDER=groq"));

        invokeValidateAndAutoFixLlmConfig(tempDir);

        assertEquals("false", System.getProperty("ARCCLAW_LLM_COMPACT_PROMPT_ENABLED"));
        assertEquals("false", System.getProperty("arcclaw.llm.compact-prompt-enabled"));
    }

    private static void invokeValidateAndAutoFixLlmConfig(Path binaryDir) throws Exception {
        Method method = LlmConfigAutoFixer.class.getDeclaredMethod("validateAndAutoFixLlmConfig", Path.class);
        method.setAccessible(true);
        try {
            method.invoke(null, binaryDir);
        } finally {
            method.setAccessible(false);
        }
    }
}
