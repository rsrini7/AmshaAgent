package com.amsha.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.amsha.common.config.ConfigKeys;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnvFileValidatorTest {

    private static final List<String> ACTIVE_PROVIDER_SETTINGS = List.of(
            ConfigKeys.LLM_PROVIDER,
            ConfigKeys.AMSHA_INFINITE_LOOP_LLM_PROVIDERS,
            ConfigKeys.AMSHA_INFINITE_LOOP_COMPACTION_PROVIDERS,
            ConfigKeys.AMSHA_INFINITE_LOOP_CLASSIFIER_PROVIDERS,
            ConfigKeys.AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS);

    private final Map<String, String> originalProviderSettings = new HashMap<>();

    @BeforeEach
    void isolateProviderConfiguration() {
        ACTIVE_PROVIDER_SETTINGS.forEach(key -> {
            originalProviderSettings.put(key, System.getProperty(key));
            // Empty system properties take precedence over shell variables, keeping each
            // fixture independent of the developer's active Amsha configuration.
            System.setProperty(key, "");
        });
    }

    @AfterEach
    void restoreProviderConfiguration() {
        originalProviderSettings.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
        originalProviderSettings.clear();
    }

    @Test
    void testValidate_validEnvFileHasNoIssues(@TempDir Path tempDir) throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.write(
                envFile,
                List.of(
                        "AMSHA_VAULT_PEPPER=dGVzdF92YXVsdF9wZXBwZXJfMTIzNDU2Nzg5MA==",
                        "AMSHA_GROQ_API_KEY=gsk_valid_key_12345",
                        "# comment line",
                        "KEY_A=hello",
                        "KEY_B=world # inline comment"));

        EnvFileValidator.ValidationResult result = EnvFileValidator.validate(envFile);

        assertFalse(result.hasIssues());
        assertEquals(0, result.issueCount());
        assertEquals(4, result.totalKeys());
    }

    @Test
    void testValidate_detectsDuplicatesAndPlaceholders(@TempDir Path tempDir) throws IOException {
        setActiveProviders("groq", "", "", "", "");
        Path envFile = tempDir.resolve(".env");
        Files.write(
                envFile,
                List.of(
                        "AMSHA_LLM_PROVIDER=groq",
                        "KEY_A=value1",
                        "KEY_A=value2",
                        "AMSHA_GROQ_API_KEY=changeme",
                        "KEY_PLACEHOLDER=<example-value>",
                        "AMSHA_SARVAM_API_KEY=your_key_here",
                        "AMSHA_OPENCODE_API_KEY=<your-key>",
                        "AMSHA_HUGGINGFACE_API_KEY=hf_your_key_here",
                        "AMSHA_MISTRAL_API_KEY=your_key_here",
                        "AMSHA_CLOUDFLARE_API_KEY=your_key_here",
                        "AMSHA_COHERE_API_KEY=your_key_here",
                        "AMSHA_CODEX_API_KEY=your_key_here",
                        "MALFORMED_LINE_WITHOUT_EQUALS"));

        EnvFileValidator.ValidationResult result = EnvFileValidator.validate(envFile);

        assertTrue(result.hasIssues());
        assertEquals(1, result.duplicateKeys().size());
        assertEquals(1, result.malformedLines().size());
        assertEquals(2, result.placeholderValues().size());
        assertTrue(result.placeholderValues().stream().anyMatch(value -> value.startsWith("AMSHA_GROQ_API_KEY ")));
        assertFalse(result.placeholderValues().toString().contains("changeme"));
    }

    @Test
    void ignoresUnusedProviderPlaceholdersForSelectedCodexSetup(@TempDir Path tempDir) throws IOException {
        setActiveProviders("codex", "codex", "codex", "codex", "ollama");
        Path envFile = tempDir.resolve(".env");
        Files.write(
                envFile,
                List.of(
                        "AMSHA_LLM_PROVIDER=codex",
                        "AMSHA_INFINITE_LOOP_LLM_PROVIDERS=codex",
                        "AMSHA_INFINITE_LOOP_COMPACTION_PROVIDERS=codex",
                        "AMSHA_INFINITE_LOOP_CLASSIFIER_PROVIDERS=codex",
                        "AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS=ollama",
                        "AMSHA_GROQ_API_KEY=<groq-api-key-1>,<groq-api-key-2>",
                        "AMSHA_OPENROUTER_API_KEY=sk-or-<your-key-1>,sk-or-<your-key-2>",
                        "AMSHA_COMPACTION_GROQ_API_KEY=<compaction-groq-api-key>",
                        "AMSHA_CLASSIFIER_GROQ_API_KEY=<classifier-groq-api-key>"));

        EnvFileValidator.ValidationResult result = EnvFileValidator.validate(envFile);

        assertFalse(result.hasIssues());
        assertEquals(0, result.placeholderValues().size());
    }

    @Test
    void reportsOnlyActiveAuxiliaryProviderPlaceholders(@TempDir Path tempDir) throws IOException {
        setActiveProviders("codex", "codex", "groq", "groq", "ollama");
        Path envFile = tempDir.resolve(".env");
        Files.write(
                envFile,
                List.of(
                        "AMSHA_LLM_PROVIDER=codex",
                        "AMSHA_INFINITE_LOOP_LLM_PROVIDERS=codex",
                        "AMSHA_INFINITE_LOOP_COMPACTION_PROVIDERS=groq",
                        "AMSHA_INFINITE_LOOP_CLASSIFIER_PROVIDERS=groq",
                        "AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS=ollama",
                        "AMSHA_GROQ_API_KEY=<shared-groq-key>",
                        "AMSHA_COMPACTION_GROQ_API_KEY=<compaction-groq-key>",
                        "AMSHA_CLASSIFIER_GROQ_API_KEY=<classifier-groq-key>",
                        "AMSHA_OPENROUTER_API_KEY=<unused-openrouter-key>"));

        EnvFileValidator.ValidationResult result = EnvFileValidator.validate(envFile);

        assertEquals(3, result.placeholderValues().size());
        assertTrue(result.placeholderValues().stream().anyMatch(value -> value.startsWith("AMSHA_GROQ_API_KEY ")));
        assertTrue(result.placeholderValues().stream()
                .anyMatch(value -> value.startsWith("AMSHA_COMPACTION_GROQ_API_KEY ")));
        assertTrue(result.placeholderValues().stream()
                .anyMatch(value -> value.startsWith("AMSHA_CLASSIFIER_GROQ_API_KEY ")));
        assertFalse(result.placeholderValues().toString().contains("unused-openrouter-key"));
    }

    @Test
    void testValidate_acceptsExplicitOptOutValues(@TempDir Path tempDir) throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.write(
                envFile,
                List.of(
                        "AMSHA_TOOLS_BRAVE_API_KEY=none",
                        "AMSHA_TOOLS_SERPER_API_KEY=false",
                        "AMSHA_TOOLS_GITHUB_TOKEN=skip"));

        EnvFileValidator.ValidationResult result = EnvFileValidator.validate(envFile);

        assertFalse(result.hasIssues());
        assertEquals(0, result.placeholderValues().size());
    }

    @Test
    void testValidate_stripsTabAndSpaceInlineComments(@TempDir Path tempDir) throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.write(
                envFile, List.of("KEY_TAB=val1\t# first comment # second comment", "KEY_SPACE=val2 # space comment"));

        EnvFileValidator.ValidationResult result = EnvFileValidator.validate(envFile);

        assertFalse(result.hasIssues());
        assertEquals(2, result.totalKeys());
    }

    @Test
    void testValidate_reportsUnmatchedQuotesWithoutThrowing(@TempDir Path tempDir) throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.write(envFile, List.of("KEY_DOUBLE=\"", "KEY_SINGLE='", "KEY_UNCLOSED=\"value"));

        EnvFileValidator.ValidationResult result = EnvFileValidator.validate(envFile);

        assertEquals(3, result.malformedLines().size());
    }

    @Test
    void recognizesAnEmptyDirectDecisionKeyAsAnApiKey(@TempDir Path tempDir) throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.write(envFile, List.of("AMSHA_DECISION_TYPESAFE_API_KEY="));

        EnvFileValidator.ValidationResult result = EnvFileValidator.validate(envFile);

        assertTrue(
                result.emptyApiKeys().stream().anyMatch(value -> value.startsWith("AMSHA_DECISION_TYPESAFE_API_KEY")));
    }

    @Test
    void testPrintReport(@TempDir Path tempDir) throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.write(
                envFile,
                List.of(
                        "KEY_A=val1",
                        "KEY_A=val2",
                        "MALFORMED_LINE",
                        "AMSHA_VAULT_PEPPER=changeme",
                        "AMSHA_GROQ_API_KEY="));

        EnvFileValidator.ValidationResult result = EnvFileValidator.validate(envFile);
        assertTrue(result.hasIssues());
        // Verify printReport runs without throwing
        EnvFileValidator.printReport(result, envFile);
    }

    private static void setActiveProviders(
            String llm, String loopLlm, String compaction, String classifier, String embedding) {
        System.setProperty(ConfigKeys.LLM_PROVIDER, llm);
        System.setProperty(ConfigKeys.AMSHA_INFINITE_LOOP_LLM_PROVIDERS, loopLlm);
        System.setProperty(ConfigKeys.AMSHA_INFINITE_LOOP_COMPACTION_PROVIDERS, compaction);
        System.setProperty(ConfigKeys.AMSHA_INFINITE_LOOP_CLASSIFIER_PROVIDERS, classifier);
        System.setProperty(ConfigKeys.AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS, embedding);
    }
}
