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
    }

    @AfterEach
    void tearDown() {
        System.setIn(originalIn);
        System.clearProperty("AMSHA_LLM_PROVIDER");
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
    void testHelperMethodsViaReflection() throws Exception {
        Method llmKey = InteractiveSetupWizard.class.getDeclaredMethod("llmApiKeyForProvider", String.class);
        llmKey.setAccessible(true);
        assertEquals("AMSHA_OPENROUTER_API_KEY", llmKey.invoke(null, "openrouter"));
        assertEquals("AMSHA_OPENAI_API_KEY", llmKey.invoke(null, "openai"));
        assertEquals("AMSHA_GEMINI_API_KEY", llmKey.invoke(null, "gemini"));
        assertEquals("AMSHA_GROQ_API_KEY", llmKey.invoke(null, "groq"));

        Method compactionKey =
                InteractiveSetupWizard.class.getDeclaredMethod("compactionApiKeyForProvider", String.class);
        compactionKey.setAccessible(true);
        assertNull(compactionKey.invoke(null, "ollama"));
        assertEquals("AMSHA_COMPACTION_OPENROUTER_API_KEY", compactionKey.invoke(null, "openrouter"));

        Method classifierKey =
                InteractiveSetupWizard.class.getDeclaredMethod("classifierApiKeyForProvider", String.class);
        classifierKey.setAccessible(true);
        assertNull(classifierKey.invoke(null, "omlx"));
        assertEquals("AMSHA_CLASSIFIER_GROQ_API_KEY", classifierKey.invoke(null, "groq"));

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
