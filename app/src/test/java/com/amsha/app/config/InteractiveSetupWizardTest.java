package com.amsha.app.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InteractiveSetupWizardTest {

    private InputStream originalIn;

    @BeforeEach
    void setUp() {
        originalIn = System.in;
        System.clearProperty("AMSHA_LLM_PROVIDER");
        System.clearProperty("AMSHA_INFINITE_LOOP_LLM_PROVIDERS");
        System.clearProperty("AMSHA_INFINITE_LOOP_COMPACTION_PROVIDERS");
        System.clearProperty("AMSHA_INFINITE_LOOP_CLASSIFIER_PROVIDERS");
        System.clearProperty("AMSHA_TELEGRAM_ENABLED");
    }

    @AfterEach
    void tearDown() {
        System.setIn(originalIn);
        System.clearProperty("AMSHA_LLM_PROVIDER");
        System.clearProperty("AMSHA_INFINITE_LOOP_LLM_PROVIDERS");
        System.clearProperty("AMSHA_INFINITE_LOOP_COMPACTION_PROVIDERS");
        System.clearProperty("AMSHA_INFINITE_LOOP_CLASSIFIER_PROVIDERS");
        System.clearProperty("AMSHA_TELEGRAM_ENABLED");
    }

    @Test
    void testRunWithSimulatedInputGroq(@TempDir Path tempDir) throws Exception {
        System.clearProperty("AMSHA_LLM_PROVIDER");
        String simulatedInput = "1\ny\nmy-api-key\nn\nn\nn\nn\n\n";
        System.setIn(new ByteArrayInputStream(simulatedInput.getBytes(StandardCharsets.UTF_8)));

        InteractiveSetupWizard.run(tempDir, new String[0]);

        Path envFile = tempDir.resolve(".env");
        assertTrue(Files.exists(envFile));
        assertNotNull(providerFromSystemOrEnvironment());
    }

    private static String providerFromSystemOrEnvironment() {
        String systemProvider = System.getProperty("AMSHA_LLM_PROVIDER");
        return systemProvider != null ? systemProvider : System.getenv("AMSHA_LLM_PROVIDER");
    }

    @Test
    void testRunWithProviderChoices(@TempDir Path tempDir) {
        String[] choices = {"2", "3", "4", "5", "6", "7", "8", "9"};
        for (String choice : choices) {
            System.clearProperty("AMSHA_LLM_PROVIDER");
            String input = choice + "\nn\nn\nn\nn\nn\n\n\n\n";
            System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
            assertDoesNotThrow(() -> InteractiveSetupWizard.run(tempDir, new String[0]));
        }
    }

    @Test
    void ensureBaseConfigs_doesNotPersistInfiniteLoopSelfReference(@TempDir Path tempDir) throws Exception {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(
                envFile,
                "AMSHA_LLM_PROVIDER=infinite-loop\n"
                        + "AMSHA_INFINITE_LOOP_LLM_PROVIDERS=infinite-loop\n"
                        + "AMSHA_INFINITE_LOOP_COMPACTION_PROVIDERS=infinite-loop\n"
                        + "AMSHA_INFINITE_LOOP_CLASSIFIER_PROVIDERS=infinite-loop\n");
        System.setProperty("AMSHA_LLM_PROVIDER", "infinite-loop");
        System.setProperty("AMSHA_INFINITE_LOOP_LLM_PROVIDERS", "infinite-loop");
        System.setProperty("AMSHA_INFINITE_LOOP_COMPACTION_PROVIDERS", "infinite-loop");
        System.setProperty("AMSHA_INFINITE_LOOP_CLASSIFIER_PROVIDERS", "infinite-loop");

        Method ensure = InteractiveSetupWizard.class.getDeclaredMethod("ensureBaseConfigsInEnv", Path.class);
        ensure.setAccessible(true);
        ensure.invoke(null, envFile);

        String content = Files.readString(envFile);
        assertTrue(content.contains("AMSHA_INFINITE_LOOP_LLM_PROVIDERS=omlx,groq,openrouter"));
        assertTrue(content.contains("AMSHA_INFINITE_LOOP_COMPACTION_PROVIDERS=groq,ollama"));
        assertTrue(content.contains("AMSHA_INFINITE_LOOP_CLASSIFIER_PROVIDERS=groq,ollama"));
        assertFalse(content.contains("AMSHA_INFINITE_LOOP_LLM_PROVIDERS=infinite-loop"));
    }

    @Test
    void ensureBaseConfigs_patchesSarvamCompactionAndClassifierDefaults(@TempDir Path tempDir) throws Exception {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, "AMSHA_LLM_PROVIDER=sarvam\n");
        System.setProperty("AMSHA_LLM_PROVIDER", "sarvam");

        Method ensure = InteractiveSetupWizard.class.getDeclaredMethod("ensureBaseConfigsInEnv", Path.class);
        ensure.setAccessible(true);
        ensure.invoke(null, envFile);

        String content = Files.readString(envFile);
        assertTrue(content.contains("AMSHA_COMPACTION_SARVAM_MODEL=sarvam-105b"));
        assertTrue(content.contains("AMSHA_COMPACTION_SARVAM_BASE_URL=https://api.sarvam.ai/v1"));
        assertTrue(content.contains("AMSHA_CLASSIFIER_SARVAM_MODEL=sarvam-105b"));
        assertTrue(content.contains("AMSHA_CLASSIFIER_SARVAM_BASE_URL=https://api.sarvam.ai/v1"));
    }

    @Test
    void ensureBaseConfigs_patchesOpenCodeCompactionAndClassifierDefaults(@TempDir Path tempDir) throws Exception {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, "AMSHA_LLM_PROVIDER=opencode\n");
        System.setProperty("AMSHA_LLM_PROVIDER", "opencode");

        Method ensure = InteractiveSetupWizard.class.getDeclaredMethod("ensureBaseConfigsInEnv", Path.class);
        ensure.setAccessible(true);
        ensure.invoke(null, envFile);

        String content = Files.readString(envFile);
        assertTrue(content.contains("AMSHA_COMPACTION_OPENCODE_MODEL=deepseek-v4-flash-free"));
        assertTrue(content.contains("AMSHA_COMPACTION_OPENCODE_BASE_URL=https://opencode.ai/zen/v1"));
        assertTrue(content.contains("AMSHA_CLASSIFIER_OPENCODE_MODEL=deepseek-v4-flash-free"));
        assertTrue(content.contains("AMSHA_CLASSIFIER_OPENCODE_BASE_URL=https://opencode.ai/zen/v1"));
    }

    @Test
    void ensureBaseConfigs_patchesHuggingFaceCompactionAndClassifierDefaults(@TempDir Path tempDir) throws Exception {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, "AMSHA_LLM_PROVIDER=huggingface\n");
        System.setProperty("AMSHA_LLM_PROVIDER", "huggingface");

        Method ensure = InteractiveSetupWizard.class.getDeclaredMethod("ensureBaseConfigsInEnv", Path.class);
        ensure.setAccessible(true);
        ensure.invoke(null, envFile);

        String content = Files.readString(envFile);
        assertTrue(content.contains("AMSHA_COMPACTION_HUGGINGFACE_MODEL=meta-llama/Llama-3.3-70B-Instruct"));
        assertTrue(content.contains("AMSHA_COMPACTION_HUGGINGFACE_BASE_URL=https://router.huggingface.co/v1"));
        assertTrue(content.contains("AMSHA_CLASSIFIER_HUGGINGFACE_MODEL=meta-llama/Llama-3.3-70B-Instruct"));
        assertTrue(content.contains("AMSHA_CLASSIFIER_HUGGINGFACE_BASE_URL=https://router.huggingface.co/v1"));
    }

    @Test
    void ensureBaseConfigs_patchesMistralCompactionAndClassifierDefaults(@TempDir Path tempDir) throws Exception {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, "AMSHA_LLM_PROVIDER=mistral\n");
        System.setProperty("AMSHA_LLM_PROVIDER", "mistral");

        Method ensure = InteractiveSetupWizard.class.getDeclaredMethod("ensureBaseConfigsInEnv", Path.class);
        ensure.setAccessible(true);
        ensure.invoke(null, envFile);

        String content = Files.readString(envFile);
        assertTrue(content.contains("AMSHA_COMPACTION_MISTRAL_MODEL=codestral-latest"));
        assertTrue(content.contains("AMSHA_COMPACTION_MISTRAL_BASE_URL=https://api.mistral.ai/v1"));
        assertTrue(content.contains("AMSHA_CLASSIFIER_MISTRAL_MODEL=codestral-latest"));
        assertTrue(content.contains("AMSHA_CLASSIFIER_MISTRAL_BASE_URL=https://api.mistral.ai/v1"));
    }

    @Test
    void ensureBaseConfigs_patchesCloudflareCompactionAndClassifierDefaults(@TempDir Path tempDir) throws Exception {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, "AMSHA_LLM_PROVIDER=cloudflare\n");
        System.setProperty("AMSHA_LLM_PROVIDER", "cloudflare");

        Method ensure = InteractiveSetupWizard.class.getDeclaredMethod("ensureBaseConfigsInEnv", Path.class);
        ensure.setAccessible(true);
        ensure.invoke(null, envFile);

        String content = Files.readString(envFile);
        assertTrue(content.contains("AMSHA_CLOUDFLARE_ACCOUNT_ID="));
        assertTrue(content.contains("AMSHA_COMPACTION_CLOUDFLARE_MODEL=@cf/meta/llama-3.3-70b-instruct-fp8-fast"));
        assertTrue(
                content.contains(
                        "AMSHA_COMPACTION_CLOUDFLARE_BASE_URL=https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1"));
        assertTrue(content.contains("AMSHA_CLASSIFIER_CLOUDFLARE_MODEL=@cf/meta/llama-3.3-70b-instruct-fp8-fast"));
        assertTrue(
                content.contains(
                        "AMSHA_CLASSIFIER_CLOUDFLARE_BASE_URL=https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1"));
    }

    @Test
    void ensureBaseConfigs_patchesCohereCompactionAndClassifierDefaults(@TempDir Path tempDir) throws Exception {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, "AMSHA_LLM_PROVIDER=cohere\n");
        System.setProperty("AMSHA_LLM_PROVIDER", "cohere");

        Method ensure = InteractiveSetupWizard.class.getDeclaredMethod("ensureBaseConfigsInEnv", Path.class);
        ensure.setAccessible(true);
        ensure.invoke(null, envFile);

        String content = Files.readString(envFile);
        assertTrue(content.contains("AMSHA_COMPACTION_COHERE_MODEL=command-r-plus-08-2024"));
        assertTrue(content.contains("AMSHA_COMPACTION_COHERE_BASE_URL=https://api.cohere.ai/compatibility/v1"));
        assertTrue(content.contains("AMSHA_CLASSIFIER_COHERE_MODEL=command-r-plus-08-2024"));
        assertTrue(content.contains("AMSHA_CLASSIFIER_COHERE_BASE_URL=https://api.cohere.ai/compatibility/v1"));
    }

    @Test
    void ensureBaseConfigs_patchesCodexCompactionAndClassifierDefaults(@TempDir Path tempDir) throws Exception {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, "AMSHA_LLM_PROVIDER=codex\n");
        System.setProperty("AMSHA_LLM_PROVIDER", "codex");

        Method ensure = InteractiveSetupWizard.class.getDeclaredMethod("ensureBaseConfigsInEnv", Path.class);
        ensure.setAccessible(true);
        ensure.invoke(null, envFile);

        String content = Files.readString(envFile);
        assertTrue(content.contains("AMSHA_COMPACTION_CODEX_MODEL=gpt-5.6-luna"));
        assertTrue(content.contains("AMSHA_COMPACTION_CODEX_BASE_URL=http://127.0.0.1:8787/v1"));
        assertTrue(content.contains("AMSHA_CLASSIFIER_CODEX_MODEL=gpt-5.6-luna"));
        assertTrue(content.contains("AMSHA_CLASSIFIER_CODEX_BASE_URL=http://127.0.0.1:8787/v1"));
    }

    @Test
    void testHelperMethodsViaReflection() throws Exception {
        Method llmKey = InteractiveSetupWizard.class.getDeclaredMethod("llmApiKeyForProvider", String.class);
        llmKey.setAccessible(true);
        assertEquals("AMSHA_OPENROUTER_API_KEY", llmKey.invoke(null, "openrouter"));
        assertEquals("AMSHA_OPENAI_API_KEY", llmKey.invoke(null, "openai"));
        assertEquals("AMSHA_GEMINI_API_KEY", llmKey.invoke(null, "gemini"));
        assertEquals("AMSHA_GROQ_API_KEY", llmKey.invoke(null, "groq"));
        assertEquals("AMSHA_SARVAM_API_KEY", llmKey.invoke(null, "sarvam"));
        assertEquals("AMSHA_OPENCODE_API_KEY", llmKey.invoke(null, "opencode"));
        assertEquals("AMSHA_HUGGINGFACE_API_KEY", llmKey.invoke(null, "huggingface"));
        assertEquals("AMSHA_MISTRAL_API_KEY", llmKey.invoke(null, "mistral"));
        assertEquals("AMSHA_CLOUDFLARE_API_KEY", llmKey.invoke(null, "cloudflare"));
        assertEquals("AMSHA_COHERE_API_KEY", llmKey.invoke(null, "cohere"));
        assertEquals("AMSHA_CODEX_API_KEY", llmKey.invoke(null, "codex"));

        Method compactionKey =
                InteractiveSetupWizard.class.getDeclaredMethod("compactionApiKeyForProvider", String.class);
        compactionKey.setAccessible(true);
        assertNull(compactionKey.invoke(null, "ollama"));
        assertNull(compactionKey.invoke(null, "codex"));
        assertEquals("AMSHA_COMPACTION_OPENROUTER_API_KEY", compactionKey.invoke(null, "openrouter"));
        assertEquals("AMSHA_COMPACTION_SARVAM_API_KEY", compactionKey.invoke(null, "sarvam"));
        assertEquals("AMSHA_COMPACTION_OPENCODE_API_KEY", compactionKey.invoke(null, "opencode"));
        assertEquals("AMSHA_COMPACTION_HUGGINGFACE_API_KEY", compactionKey.invoke(null, "huggingface"));
        assertEquals("AMSHA_COMPACTION_MISTRAL_API_KEY", compactionKey.invoke(null, "mistral"));
        assertEquals("AMSHA_COMPACTION_CLOUDFLARE_API_KEY", compactionKey.invoke(null, "cloudflare"));
        assertEquals("AMSHA_COMPACTION_COHERE_API_KEY", compactionKey.invoke(null, "cohere"));

        Method classifierKey =
                InteractiveSetupWizard.class.getDeclaredMethod("classifierApiKeyForProvider", String.class);
        classifierKey.setAccessible(true);
        assertNull(classifierKey.invoke(null, "omlx"));
        assertNull(classifierKey.invoke(null, "codex"));
        assertEquals("AMSHA_CLASSIFIER_GROQ_API_KEY", classifierKey.invoke(null, "groq"));
        assertEquals("AMSHA_CLASSIFIER_SARVAM_API_KEY", classifierKey.invoke(null, "sarvam"));
        assertEquals("AMSHA_CLASSIFIER_OPENCODE_API_KEY", classifierKey.invoke(null, "opencode"));
        assertEquals("AMSHA_CLASSIFIER_HUGGINGFACE_API_KEY", classifierKey.invoke(null, "huggingface"));
        assertEquals("AMSHA_CLASSIFIER_MISTRAL_API_KEY", classifierKey.invoke(null, "mistral"));
        assertEquals("AMSHA_CLASSIFIER_CLOUDFLARE_API_KEY", classifierKey.invoke(null, "cloudflare"));
        assertEquals("AMSHA_CLASSIFIER_COHERE_API_KEY", classifierKey.invoke(null, "cohere"));

        Method genRand = InteractiveSetupWizard.class.getDeclaredMethod("generateRandomValue");
        genRand.setAccessible(true);
        String rand = (String) genRand.invoke(null);
        assertNotNull(rand);
        assertFalse(rand.isBlank());

        Method isTelegram = InteractiveSetupWizard.class.getDeclaredMethod("isTelegramKey", String.class);
        isTelegram.setAccessible(true);
        assertTrue((Boolean) isTelegram.invoke(null, "TELEGRAM_BOT_TOKEN"));
        assertFalse((Boolean) isTelegram.invoke(null, "AMSHA_OPENAI_API_KEY"));
    }
}
