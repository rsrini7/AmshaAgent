package com.amsha.app.util;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Deep unit tests for {@link CliUtils} and related utility classes.
 * Tests public methods and private static helpers via reflection.
 */
class CliUtilsTest {

    // Default per-slot API keys resolved by the per-provider logic in ProviderKeySpecs.
    // Tests below set AMSHA_INFINITE_LOOP_*_PROVIDERS to switch slot providers.
    private static final String[] CORE_KEYS = {
        "AMSHA_VAULT_PEPPER",
        "AMSHA_AUDIT_HMAC_KEY",
        "AMSHA_API_KEYS_INTERNAL",
        "AMSHA_API_KEYS_CI",
        "AMSHA_AUTH_HMAC_KEY",
    };

    private static final String[] PER_PROVIDER_KEYS = {
        "AMSHA_COMPACTION_GROQ_API_KEY",
        "AMSHA_COMPACTION_CEREBRAS_API_KEY",
        "AMSHA_COMPACTION_OPENROUTER_API_KEY",
        "AMSHA_COMPACTION_OPENAI_API_KEY",
        "AMSHA_COMPACTION_GEMINI_API_KEY",
        "AMSHA_COMPACTION_NVIDIA_API_KEY",
        "AMSHA_COMPACTION_OPENCODE_API_KEY",
        "AMSHA_COMPACTION_HUGGINGFACE_API_KEY",
        "AMSHA_CLASSIFIER_GROQ_API_KEY",
        "AMSHA_CLASSIFIER_CEREBRAS_API_KEY",
        "AMSHA_CLASSIFIER_OPENROUTER_API_KEY",
        "AMSHA_CLASSIFIER_OPENAI_API_KEY",
        "AMSHA_CLASSIFIER_GEMINI_API_KEY",
        "AMSHA_CLASSIFIER_NVIDIA_API_KEY",
        "AMSHA_CLASSIFIER_OPENCODE_API_KEY",
        "AMSHA_CLASSIFIER_HUGGINGFACE_API_KEY",
        "AMSHA_EMBEDDING_JINA_API_KEY",
        "AMSHA_EMBEDDING_OPENAI_API_KEY",
    };

    private static final String[] TELEGRAM_KEYS = {
        "TELEGRAM_BOT_TOKEN", "AMSHA_TELEGRAM_ALLOWED_USER_IDS", "AMSHA_TELEGRAM_ALLOWED_USERS",
    };

    private static final String[] OPTIONAL_TOOL_KEYS = {
        "AMSHA_TOOLS_GITHUB_TOKEN", "AMSHA_TOOLS_SERPER_API_KEY", "AMSHA_TOOLS_BRAVE_API_KEY",
    };

    @AfterEach
    void cleanupSystemProperties() {
        for (String k : CORE_KEYS) System.clearProperty(k);
        for (String k : PER_PROVIDER_KEYS) System.clearProperty(k);
        for (String k : TELEGRAM_KEYS) System.clearProperty(k);
        for (String k : OPTIONAL_TOOL_KEYS) System.clearProperty(k);
        System.clearProperty("AMSHA_LLM_PROVIDER");
        System.clearProperty("AMSHA_INFINITE_LOOP_COMPACTION_PROVIDERS");
        System.clearProperty("AMSHA_INFINITE_LOOP_CLASSIFIER_PROVIDERS");
        System.clearProperty("AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS");
        System.clearProperty("AMSHA_TELEGRAM_ENABLED");
    }

    // -----------------------------------------------------------------------
    // VarSpec record
    // -----------------------------------------------------------------------

    @Test
    void varSpec_constructorAndGetters() {
        var spec = new VarSpec("KEY", "desc", "step1", "step2");
        assertEquals("KEY", spec.key());
        assertEquals("desc", spec.description());
        assertArrayEquals(new String[] {"step1", "step2"}, spec.howToGet());
    }

    @Test
    void varSpec_singleHowToGetStep() {
        var spec = new VarSpec("K", "D", "only step");
        assertEquals(1, spec.howToGet().length);
        assertEquals("only step", spec.howToGet()[0]);
    }

    // -----------------------------------------------------------------------
    // isSet (private static) — tested via reflection against CliUtils
    // -----------------------------------------------------------------------

    @Test
    void isSet_null_returnsFalse() throws Exception {
        assertFalse(invokeIsSet("NONEXISTENT_KEY_12345"));
    }

    @Test
    void isSet_blank_returnsFalse() throws Exception {
        System.setProperty("TEST_IS_SET_BLANK", "   ");
        assertFalse(invokeIsSet("TEST_IS_SET_BLANK"));
        System.clearProperty("TEST_IS_SET_BLANK");
    }

    @Test
    void isSet_changeme_returnsFalse() throws Exception {
        System.setProperty("TEST_IS_SET_CHANGE", "changeme");
        assertFalse(invokeIsSet("TEST_IS_SET_CHANGE"));
        System.clearProperty("TEST_IS_SET_CHANGE");
    }

    @Test
    void isSet_angleBracket_returnsFalse() throws Exception {
        System.setProperty("TEST_IS_SET_ANGLE", "<your-key>");
        assertFalse(invokeIsSet("TEST_IS_SET_ANGLE"));
        System.clearProperty("TEST_IS_SET_ANGLE");
    }

    @Test
    void isSet_yourHerePattern_returnsFalse() throws Exception {
        System.setProperty("TEST_IS_SET_YOUR", "your_key_here");
        assertFalse(invokeIsSet("TEST_IS_SET_YOUR"));
        System.clearProperty("TEST_IS_SET_YOUR");
    }

    @Test
    void isSet_none_returnsFalse() throws Exception {
        System.setProperty("TEST_IS_SET_NONE", "none");
        assertFalse(invokeIsSet("TEST_IS_SET_NONE"));
        System.clearProperty("TEST_IS_SET_NONE");
    }

    @Test
    void isSet_repeatedX_returnsFalse() throws Exception {
        System.setProperty("TEST_IS_SET_XXX", "jina_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        assertFalse(invokeIsSet("TEST_IS_SET_XXX"));
        System.clearProperty("TEST_IS_SET_XXX");
    }

    @Test
    void isSet_validKey_returnsTrue() throws Exception {
        System.setProperty("TEST_IS_SET_VALID", "gsk_real_key_abc123");
        assertTrue(invokeIsSet("TEST_IS_SET_VALID"));
        System.clearProperty("TEST_IS_SET_VALID");
    }

    @Test
    void isSet_caseInsensitiveNone() throws Exception {
        System.setProperty("TEST_IS_SET_NONE2", "NONE");
        assertFalse(invokeIsSet("TEST_IS_SET_NONE2"));
        System.clearProperty("TEST_IS_SET_NONE2");
    }

    @Test
    void isSet_shortXString_returnsTrue() throws Exception {
        // Less than 8 repeated x's should be valid
        System.setProperty("TEST_IS_SET_SHORTX", "jina_xxxxxxx");
        assertTrue(invokeIsSet("TEST_IS_SET_SHORTX"));
        System.clearProperty("TEST_IS_SET_SHORTX");
    }

    // -----------------------------------------------------------------------
    // isExplicitOptOut (private static)
    // -----------------------------------------------------------------------

    @Test
    void isExplicitOptOut_none_returnsTrue() throws Exception {
        System.setProperty("TEST_OPTOUT_NONE", "none");
        assertTrue(invokeIsExplicitOptOut("TEST_OPTOUT_NONE"));
        System.clearProperty("TEST_OPTOUT_NONE");
    }

    @Test
    void isExplicitOptOut_false_returnsTrue() throws Exception {
        System.setProperty("TEST_OPTOUT_FALSE", "false");
        assertTrue(invokeIsExplicitOptOut("TEST_OPTOUT_FALSE"));
        System.clearProperty("TEST_OPTOUT_FALSE");
    }

    @Test
    void isExplicitOptOut_skip_returnsTrue() throws Exception {
        System.setProperty("TEST_OPTOUT_SKIP", "skip");
        assertTrue(invokeIsExplicitOptOut("TEST_OPTOUT_SKIP"));
        System.clearProperty("TEST_OPTOUT_SKIP");
    }

    @Test
    void isExplicitOptOut_caseInsensitive() throws Exception {
        System.setProperty("TEST_OPTOUT_CI", "FALSE");
        assertTrue(invokeIsExplicitOptOut("TEST_OPTOUT_CI"));
        System.clearProperty("TEST_OPTOUT_CI");
    }

    @Test
    void isExplicitOptOut_realKey_returnsFalse() throws Exception {
        System.setProperty("TEST_OPTOUT_REAL", "gsk_abc123");
        assertFalse(invokeIsExplicitOptOut("TEST_OPTOUT_REAL"));
        System.clearProperty("TEST_OPTOUT_REAL");
    }

    @Test
    void isExplicitOptOut_nullKey_returnsFalse() throws Exception {
        assertFalse(invokeIsExplicitOptOut("NONEXISTENT_OPTOUT_KEY_12345"));
    }

    // -----------------------------------------------------------------------
    // llmKeySpecFor (ProviderKeySpecs — public static)
    // -----------------------------------------------------------------------

    @Test
    void llmKeySpecFor_groq_default() {
        assertEquals(
                "AMSHA_GROQ_API_KEY", ProviderKeySpecs.llmKeySpecFor("groq").key());
    }

    @Test
    void llmKeySpecFor_openrouter() {
        assertEquals(
                "AMSHA_OPENROUTER_API_KEY",
                ProviderKeySpecs.llmKeySpecFor("openrouter").key());
    }

    @Test
    void llmKeySpecFor_openai() {
        assertEquals(
                "AMSHA_OPENAI_API_KEY", ProviderKeySpecs.llmKeySpecFor("openai").key());
    }

    @Test
    void llmKeySpecFor_gemini() {
        assertEquals(
                "AMSHA_GEMINI_API_KEY", ProviderKeySpecs.llmKeySpecFor("gemini").key());
    }

    @Test
    void llmKeySpecFor_nvidia() {
        assertEquals(
                "AMSHA_NVIDIA_API_KEY", ProviderKeySpecs.llmKeySpecFor("nvidia").key());
    }

    @Test
    void llmKeySpecFor_cerebras() {
        assertEquals(
                "AMSHA_CEREBRAS_API_KEY",
                ProviderKeySpecs.llmKeySpecFor("cerebras").key());
    }

    @Test
    void llmKeySpecFor_sarvam() {
        assertEquals(
                "AMSHA_SARVAM_API_KEY", ProviderKeySpecs.llmKeySpecFor("sarvam").key());
    }

    @Test
    void llmKeySpecFor_opencode() {
        assertEquals(
                "AMSHA_OPENCODE_API_KEY",
                ProviderKeySpecs.llmKeySpecFor("opencode").key());
    }

    @Test
    void llmKeySpecFor_omlx() {
        assertEquals(
                "AMSHA_OMLX_API_KEY", ProviderKeySpecs.llmKeySpecFor("omlx").key());
    }

    @Test
    void llmKeySpecFor_ollama_returnsNull() {
        assertNull(ProviderKeySpecs.llmKeySpecFor("ollama"));
    }

    @Test
    void llmKeySpecFor_unknownDefaultsToGroq() {
        assertEquals(
                "AMSHA_GROQ_API_KEY",
                ProviderKeySpecs.llmKeySpecFor("something-else").key());
    }

    // -----------------------------------------------------------------------
    // getMissingVarSpecs (public) — exercises the full switch + core + optional
    // -----------------------------------------------------------------------

    @Test
    void getMissingVarSpecs_withAllVarsSet_returnsEmpty() {
        setAllVars();
        List<VarSpec> missing = CliUtils.getMissingVarSpecs(new String[] {});
        assertTrue(missing.isEmpty(), "Expected no missing vars when all are set");
    }

    @Test
    void getMissingVarSpecs_optedOutToolKeys_notIncluded() {
        setAllVars();
        // Now opt out optional tools
        System.setProperty("AMSHA_TOOLS_GITHUB_TOKEN", "none");
        System.setProperty("AMSHA_TOOLS_SERPER_API_KEY", "skip");
        System.setProperty("AMSHA_TOOLS_BRAVE_API_KEY", "false");
        List<VarSpec> missing = CliUtils.getMissingVarSpecs(new String[] {});
        assertTrue(missing.isEmpty());
    }

    @Test
    void getMissingVarSpecs_telegramDisabled_doesNotRequireTelegramFields() {
        setAllVars();
        System.clearProperty("TELEGRAM_BOT_TOKEN");
        System.clearProperty("AMSHA_TELEGRAM_ALLOWED_USER_IDS");
        System.clearProperty("AMSHA_TELEGRAM_ALLOWED_USERS");
        System.setProperty("AMSHA_TELEGRAM_ENABLED", "false");

        List<VarSpec> missing = CliUtils.getMissingVarSpecs(new String[] {});

        assertTrue(missing.stream().noneMatch(spec -> spec.key().startsWith("TELEGRAM_")));
        assertTrue(missing.stream().noneMatch(spec -> spec.key().startsWith("AMSHA_TELEGRAM_")));
    }

    @Test
    void getMissingVarSpecs_attachModeStillRequiresTelegramFields() {
        setAllVars();
        System.clearProperty("TELEGRAM_BOT_TOKEN");
        System.clearProperty("AMSHA_TELEGRAM_ALLOWED_USER_IDS");
        System.clearProperty("AMSHA_TELEGRAM_ALLOWED_USERS");
        System.setProperty("AMSHA_TELEGRAM_ENABLED", "false");

        List<VarSpec> missing = CliUtils.getMissingVarSpecs(new String[] {"attach"});

        assertTrue(missing.stream().anyMatch(spec -> spec.key().equals("TELEGRAM_BOT_TOKEN")));
    }

    @Test
    void testWarnTelegramIfPartial() throws Exception {
        Method warnMethod = CliUtils.class.getDeclaredMethod("warnTelegramIfPartial");
        warnMethod.setAccessible(true);

        assertDoesNotThrow(() -> warnMethod.invoke(null));

        System.setProperty("AMSHA_TELEGRAM_ENABLED", "true");
        assertDoesNotThrow(() -> warnMethod.invoke(null));

        System.setProperty("TELEGRAM_BOT_TOKEN", "bot123");
        assertDoesNotThrow(() -> warnMethod.invoke(null));

        System.clearProperty("AMSHA_TELEGRAM_ENABLED");
        assertDoesNotThrow(() -> warnMethod.invoke(null));
    }

    // -----------------------------------------------------------------------
    // validateRequiredEnvVars — tests the happy path (no exit)
    // -----------------------------------------------------------------------

    @Test
    void validateRequiredEnvVars_helpFlag_doesNothing() {
        // --help should not throw or exit
        assertDoesNotThrow(() -> CliUtils.validateRequiredEnvVars(new String[] {"--help"}));
    }

    @Test
    void validateRequiredEnvVars_hFlag_doesNothing() {
        assertDoesNotThrow(() -> CliUtils.validateRequiredEnvVars(new String[] {"-h"}));
    }

    @Test
    void validateRequiredEnvVars_versionFlag_doesNothing() {
        assertDoesNotThrow(() -> CliUtils.validateRequiredEnvVars(new String[] {"--version"}));
    }

    @Test
    void validateRequiredEnvVars_vFlag_doesNothing() {
        assertDoesNotThrow(() -> CliUtils.validateRequiredEnvVars(new String[] {"-v"}));
    }

    @Test
    void validateRequiredEnvVars_emptyArgs_doesNotExit() {
        // When all vars are set, empty args (no mode) should not exit
        setAllVars();
        assertDoesNotThrow(() -> CliUtils.validateRequiredEnvVars(new String[] {}));
    }

    @Test
    void getMissingVarSpecs_otherLlmProviders() {
        setAllVars();
        String[] providers = {"openrouter", "openai", "gemini", "nvidia", "cerebras", "omlx", "ollama"};
        for (String p : providers) {
            System.setProperty("AMSHA_LLM_PROVIDER", p);
            // Should not throw and should handle provider resolution
            List<VarSpec> missing = CliUtils.getMissingVarSpecs(new String[] {});
            assertNotNull(missing);
        }
    }

    @Test
    void getMissingVarSpecs_customSlotProviders() {
        setAllVars();
        System.setProperty("AMSHA_INFINITE_LOOP_COMPACTION_PROVIDERS", "cerebras,groq");
        System.setProperty("AMSHA_INFINITE_LOOP_CLASSIFIER_PROVIDERS", "openrouter,ollama");
        System.setProperty("AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS", "openai");
        List<VarSpec> missing = CliUtils.getMissingVarSpecs(new String[] {});
        assertNotNull(missing);
    }

    @Test
    void getMissingVarSpecs_optionalToolsMissing_returnsMissingSpecs() {
        setAllVars();
        System.setProperty("AMSHA_TOOLS_GITHUB_TOKEN", "");
        System.setProperty("AMSHA_TOOLS_SERPER_API_KEY", "");
        System.setProperty("AMSHA_TOOLS_BRAVE_API_KEY", "");

        List<VarSpec> missing = CliUtils.getMissingVarSpecs(new String[] {});
        assertEquals(3, missing.size());
        assertTrue(missing.stream().anyMatch(s -> s.key().equals("AMSHA_TOOLS_GITHUB_TOKEN")));
        assertTrue(missing.stream().anyMatch(s -> s.key().equals("AMSHA_TOOLS_SERPER_API_KEY")));
        assertTrue(missing.stream().anyMatch(s -> s.key().equals("AMSHA_TOOLS_BRAVE_API_KEY")));
    }

    // -----------------------------------------------------------------------
    // Reflection helpers for CliUtils private methods
    // -----------------------------------------------------------------------

    private static boolean invokeIsSet(String key) throws Exception {
        Method m = CliUtils.class.getDeclaredMethod("isSet", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, key);
    }

    private static boolean invokeIsExplicitOptOut(String key) throws Exception {
        Method m = CliUtils.class.getDeclaredMethod("isExplicitOptOut", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, key);
    }

    private void setAllVars() {
        for (String k : CORE_KEYS) System.setProperty(k, "test-value-123");
        for (String k : PER_PROVIDER_KEYS) System.setProperty(k, "test-value-123");
        for (String k : TELEGRAM_KEYS) System.setProperty(k, "test-value-123");
        for (String k : OPTIONAL_TOOL_KEYS) System.setProperty(k, "test-value-123");
        // Provider key
        System.setProperty("AMSHA_GROQ_API_KEY", "gsk_test123");
        // Default slot providers (groq for compaction/classifier, ollama for embedding)
        // already covered by the COMPACTION_GROQ / CLASSIFIER_GROQ / embedding keys
        // in PER_PROVIDER_KEYS — nothing else to do.
    }
}
