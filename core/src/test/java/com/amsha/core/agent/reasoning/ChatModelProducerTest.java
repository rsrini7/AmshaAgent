package com.amsha.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.amsha.llm.api.AgentChatModelProvider;
import com.amsha.llm.api.AgentLlmConfig;
import com.amsha.llm.api.ChatModelCreationPort;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultConstants;
import com.amsha.security.api.VaultKey;

import org.junit.jupiter.api.Test;

/**
 * Deep unit tests for {@link ChatModelProducer}.
 * Tests the static PROVIDER_BASE_URLS map and the private clean() method.
 * CDI-injected methods (chatModel, structuredOutputMode) require @QuarkusTest.
 */
class ChatModelProducerTest {

    // -----------------------------------------------------------------------
    // PROVIDER_BASE_URLS — static final map
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, String> getProviderBaseUrls() throws Exception {
        Field f = ChatModelProducer.class.getDeclaredField("PROVIDER_BASE_URLS");
        f.setAccessible(true);
        return (Map<String, String>) f.get(null);
    }

    @Test
    void providerBaseUrls_containsGroq() throws Exception {
        Map<String, String> urls = getProviderBaseUrls();
        assertEquals("https://api.groq.com/openai/v1", urls.get("groq"));
    }

    @Test
    void providerBaseUrls_containsOpenai() throws Exception {
        Map<String, String> urls = getProviderBaseUrls();
        assertEquals("https://api.openai.com/v1", urls.get("openai"));
    }

    @Test
    void providerBaseUrls_containsOpenrouter() throws Exception {
        Map<String, String> urls = getProviderBaseUrls();
        assertEquals("https://openrouter.ai/api/v1", urls.get("openrouter"));
    }

    @Test
    void providerBaseUrls_containsCerebras() throws Exception {
        Map<String, String> urls = getProviderBaseUrls();
        assertEquals("https://api.cerebras.ai/v1", urls.get("cerebras"));
    }

    @Test
    void providerBaseUrls_containsNvidia() throws Exception {
        Map<String, String> urls = getProviderBaseUrls();
        assertEquals("https://integrate.api.nvidia.com/v1", urls.get("nvidia"));
    }

    @Test
    void providerBaseUrls_containsOllama() throws Exception {
        Map<String, String> urls = getProviderBaseUrls();
        assertEquals("http://localhost:11434", urls.get("ollama"));
    }

    @Test
    void providerBaseUrls_containsOmlx() throws Exception {
        Map<String, String> urls = getProviderBaseUrls();
        assertEquals("http://127.0.0.1:8000/v1", urls.get("omlx"));
    }

    @Test
    void providerBaseUrls_containsLlamacpp() throws Exception {
        Map<String, String> urls = getProviderBaseUrls();
        assertEquals(ModelDefaults.DEFAULT_LLAMACPP_BASE_URL, urls.get("llamacpp"));
    }

    @Test
    void providerBaseUrls_containsSarvam() throws Exception {
        Map<String, String> urls = getProviderBaseUrls();
        assertEquals("https://api.sarvam.ai/v1", urls.get("sarvam"));
    }

    @Test
    void providerBaseUrls_containsOpencode() throws Exception {
        Map<String, String> urls = getProviderBaseUrls();
        assertEquals("https://opencode.ai/zen/v1", urls.get("opencode"));
    }

    @Test
    void providerBaseUrls_containsHuggingface() throws Exception {
        Map<String, String> urls = getProviderBaseUrls();
        assertEquals("https://router.huggingface.co/v1", urls.get("huggingface"));
    }

    @Test
    void providerBaseUrls_containsMistral() throws Exception {
        Map<String, String> urls = getProviderBaseUrls();
        assertEquals("https://api.mistral.ai/v1", urls.get("mistral"));
    }

    @Test
    void providerBaseUrls_containsCloudflare() throws Exception {
        Map<String, String> urls = getProviderBaseUrls();
        assertEquals("https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1", urls.get("cloudflare"));
    }

    @Test
    void providerBaseUrls_containsCohere() throws Exception {
        Map<String, String> urls = getProviderBaseUrls();
        assertEquals("https://api.cohere.ai/compatibility/v1", urls.get("cohere"));
    }

    @Test
    void providerBaseUrls_containsCodex() throws Exception {
        Map<String, String> urls = getProviderBaseUrls();
        assertEquals("https://chatgpt.com/backend-api/codex", urls.get("codex"));
    }

    @Test
    void providerBaseUrls_containsGemini() throws Exception {
        Map<String, String> urls = getProviderBaseUrls();
        assertEquals("https://generativelanguage.googleapis.com/v1beta", urls.get("gemini"));
    }

    @Test
    void providerBaseUrls_containsClaudeCode() throws Exception {
        Map<String, String> urls = getProviderBaseUrls();
        assertEquals("https://api.anthropic.com", urls.get("claude-code"));
    }

    @Test
    void providerBaseUrls_containsAgy() throws Exception {
        Map<String, String> urls = getProviderBaseUrls();
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", urls.get("agy"));
    }

    @Test
    void providerBaseUrls_containsAnthropic() throws Exception {
        Map<String, String> urls = getProviderBaseUrls();
        assertEquals("https://api.anthropic.com", urls.get("anthropic"));
    }

    @Test
    void providerBaseUrls_containsLlamaCpp() throws Exception {
        Map<String, String> urls = getProviderBaseUrls();
        assertEquals("http://127.0.0.1:9000/v1", urls.get("llamacpp"));
    }

    @Test
    void providerBaseUrls_containsFoundry() throws Exception {
        Map<String, String> urls = getProviderBaseUrls();
        assertEquals(ModelDefaults.DEFAULT_FOUNDRY_BASE_URL, urls.get("foundry"));
    }

    @Test
    void providerBaseUrls_hasExpectedCount() throws Exception {
        Map<String, String> urls = getProviderBaseUrls();
        assertEquals(22, urls.size());
    }

    @Test
    void providerBaseUrls_unknownProvider_returnsNull() throws Exception {
        Map<String, String> urls = getProviderBaseUrls();
        assertNull(urls.get("unknown-provider"));
    }

    // -----------------------------------------------------------------------
    // clean() — private method
    // -----------------------------------------------------------------------

    @Test
    void clean_null_returnsNull() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        Method m = ChatModelProducer.class.getDeclaredMethod("clean", String.class);
        m.setAccessible(true);
        assertNull(m.invoke(producer, (String) null));
    }

    @Test
    void clean_noHash_returnsTrimmed() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        Method m = ChatModelProducer.class.getDeclaredMethod("clean", String.class);
        m.setAccessible(true);
        assertEquals("groq", m.invoke(producer, "groq"));
    }

    @Test
    void clean_withHash_returnsBeforeHash() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        Method m = ChatModelProducer.class.getDeclaredMethod("clean", String.class);
        m.setAccessible(true);
        assertEquals("groq", m.invoke(producer, "groq#some-suffix"));
    }

    @Test
    void clean_withHashAndSpaces_returnsTrimmedBeforeHash() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        Method m = ChatModelProducer.class.getDeclaredMethod("clean", String.class);
        m.setAccessible(true);
        assertEquals("openai", m.invoke(producer, "  openai#variant  "));
    }

    @Test
    void clean_multipleHashes_splitsOnFirstOnly() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        Method m = ChatModelProducer.class.getDeclaredMethod("clean", String.class);
        m.setAccessible(true);
        assertEquals("provider", m.invoke(producer, "provider#part1#part2"));
    }

    @Test
    void clean_emptyString_returnsEmpty() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        Method m = ChatModelProducer.class.getDeclaredMethod("clean", String.class);
        m.setAccessible(true);
        assertEquals("", m.invoke(producer, ""));
    }

    @Test
    void clean_blankString_returnsEmpty() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        Method m = ChatModelProducer.class.getDeclaredMethod("clean", String.class);
        m.setAccessible(true);
        assertEquals("", m.invoke(producer, "   "));
    }

    // -----------------------------------------------------------------------
    // resolveProvider() — private method (tests vault-priority + fallback)
    // Requires setting the 'provider' and 'vaultPriority' fields via reflection
    // -----------------------------------------------------------------------

    @Test
    void resolveProvider_noVaultPriority_returnsProviderField() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        setField(producer, "provider", "openai");
        setField(producer, "vaultPriority", false);

        Method m = ChatModelProducer.class.getDeclaredMethod("resolveProvider");
        m.setAccessible(true);
        String result = (String) m.invoke(producer);
        assertEquals("openai", result);
    }

    @Test
    void resolveProvider_nullProvider_returnsGroq() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        setField(producer, "provider", null);
        setField(producer, "vaultPriority", false);

        Method m = ChatModelProducer.class.getDeclaredMethod("resolveProvider");
        m.setAccessible(true);
        String result = (String) m.invoke(producer);
        assertEquals("groq", result);
    }

    // -----------------------------------------------------------------------
    // findProvider() — private method (tests iteration and not-found)
    // -----------------------------------------------------------------------

    @Test
    void findProvider_notFound_throwsRuntime() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        setField(producer, "provider", "nonexistent");

        // Create an empty CDI Instance mock using a simple list
        // We'll use reflection to set an empty iterable
        Instance<AgentChatModelProvider> emptyInstance = createEmptyInstance();
        setField(producer, "providers", emptyInstance);

        Method m = ChatModelProducer.class.getDeclaredMethod("findProvider", String.class);
        m.setAccessible(true);

        Exception thrown = assertThrows(Exception.class, () -> {
            try {
                m.invoke(producer, "nonexistent");
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
        assertTrue(thrown instanceof RuntimeException);
        assertTrue(thrown.getMessage().contains("No AgentChatModelProvider found"));
        assertTrue(thrown.getMessage().contains("nonexistent"));
    }

    @Test
    void findProvider_nullName_defaultsToGroq() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        setField(producer, "provider", "groq");

        // Create a mock provider
        AgentChatModelProvider mockProvider = new AgentChatModelProvider() {
            @Override
            public String providerName() {
                return "groq";
            }

            @Override
            public ChatModel create() {
                return null;
            }
        };

        Instance<AgentChatModelProvider> mockInstance = createInstanceWith(mockProvider);
        setField(producer, "providers", mockInstance);

        Method m = ChatModelProducer.class.getDeclaredMethod("findProvider", String.class);
        m.setAccessible(true);
        AgentChatModelProvider result = (AgentChatModelProvider) m.invoke(producer, (String) null);
        assertNotNull(result);
        assertEquals("groq", result.providerName());
    }

    @Test
    void findProvider_caseInsensitive() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        setField(producer, "provider", "GROQ");

        AgentChatModelProvider mockProvider = new AgentChatModelProvider() {
            @Override
            public String providerName() {
                return "groq";
            }

            @Override
            public ChatModel create() {
                return null;
            }
        };

        Instance<AgentChatModelProvider> mockInstance = createInstanceWith(mockProvider);
        setField(producer, "providers", mockInstance);

        Method m = ChatModelProducer.class.getDeclaredMethod("findProvider", String.class);
        m.setAccessible(true);
        AgentChatModelProvider result = (AgentChatModelProvider) m.invoke(producer, "GROQ");
        assertNotNull(result);
        assertEquals("groq", result.providerName());
    }

    @Test
    void findProvider_withHashSuffix_stripsHash() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        setField(producer, "provider", "openai#variant");

        AgentChatModelProvider mockProvider = new AgentChatModelProvider() {
            @Override
            public String providerName() {
                return "openai";
            }

            @Override
            public ChatModel create() {
                return null;
            }
        };

        Instance<AgentChatModelProvider> mockInstance = createInstanceWith(mockProvider);
        setField(producer, "providers", mockInstance);

        Method m = ChatModelProducer.class.getDeclaredMethod("findProvider", String.class);
        m.setAccessible(true);
        AgentChatModelProvider result = (AgentChatModelProvider) m.invoke(producer, "openai#variant");
        assertNotNull(result);
        assertEquals("openai", result.providerName());
    }

    @Test
    void findProvider_unknownProvider_throwsIllegalStateException() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        setField(producer, "providers", createEmptyInstance());

        Method m = ChatModelProducer.class.getDeclaredMethod("findProvider", String.class);
        m.setAccessible(true);
        InvocationTargetException ite =
                assertThrows(InvocationTargetException.class, () -> m.invoke(producer, "non-existent-provider"));
        assertTrue(ite.getCause() instanceof IllegalStateException);
        assertTrue(ite.getCause().getMessage().contains("No AgentChatModelProvider found for"));
    }

    @Test
    void findCreationPort_whenNoBean_returnsEmpty() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        BeanManager mockBm = mock(BeanManager.class);
        when(mockBm.getBeans(Object.class)).thenReturn(Collections.emptySet());
        setField(producer, "beanManager", mockBm);

        Method m = ChatModelProducer.class.getDeclaredMethod("findCreationPort");
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        Optional<ChatModelCreationPort> result = (Optional<ChatModelCreationPort>) m.invoke(producer);
        assertTrue(result.isEmpty());
    }

    @Test
    void creationPort_whenNoBean_throwsIllegalStateException() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        BeanManager mockBm = mock(BeanManager.class);
        when(mockBm.getBeans(Object.class)).thenReturn(Collections.emptySet());
        setField(producer, "beanManager", mockBm);

        Method m = ChatModelProducer.class.getDeclaredMethod("creationPort");
        m.setAccessible(true);
        InvocationTargetException ite = assertThrows(InvocationTargetException.class, () -> m.invoke(producer));
        assertTrue(ite.getCause() instanceof IllegalStateException);
        assertTrue(ite.getCause().getMessage().contains("No ChatModelCreationPort bean found"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void creationPort_whenBeanPresent_returnsResolvedBean() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        BeanManager mockBm = mock(BeanManager.class);
        Bean<ChatModelCreationPort> mockBean = mock(Bean.class);
        doReturn(ChatModelCreationPort.class).when(mockBean).getBeanClass();
        doReturn(Set.of(ChatModelCreationPort.class)).when(mockBean).getTypes();
        when(mockBm.getBeans(Object.class)).thenReturn(Set.of(mockBean));
        doReturn(mockBean).when(mockBm).resolve(anySet());
        ChatModelCreationPort mockPort = mock(ChatModelCreationPort.class);
        when(mockBm.getReference(eq(mockBean), eq(ChatModelCreationPort.class), any()))
                .thenReturn(mockPort);
        setField(producer, "beanManager", mockBm);

        Method m = ChatModelProducer.class.getDeclaredMethod("creationPort");
        m.setAccessible(true);
        ChatModelCreationPort result = (ChatModelCreationPort) m.invoke(producer);
        assertSame(mockPort, result);
    }

    // -----------------------------------------------------------------------
    // readVaultString() — vault read primitive shared by every resolution path
    // -----------------------------------------------------------------------

    @Test
    void readVaultString_presentKey_returnsTrimmedValue() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        setField(producer, "vault", mockVault(Map.of(VaultConstants.LLM_MODEL_KEY, "  llama-3.1-70b  ")));

        assertEquals(
                "llama-3.1-70b",
                invokePrivate(
                        producer, "readVaultString", new Class<?>[] {String.class}, VaultConstants.LLM_MODEL_KEY));
    }

    @Test
    void readVaultString_absentKey_returnsNull() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        setField(producer, "vault", mockVault(Map.of()));

        assertNull(invokePrivate(
                producer, "readVaultString", new Class<?>[] {String.class}, VaultConstants.LLM_MODEL_KEY));
    }

    @Test
    void readVaultString_whitespaceOnlyValue_returnsEmptyString() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        setField(producer, "vault", mockVault(Map.of(VaultConstants.LLM_MODEL_KEY, "   ")));

        assertEquals(
                "",
                invokePrivate(
                        producer, "readVaultString", new Class<?>[] {String.class}, VaultConstants.LLM_MODEL_KEY));
    }

    // -----------------------------------------------------------------------
    // readVaultKeyForProvider() — the switch over OAuth token keys.
    // Each arm is pinned against the key name that must actually be read.
    // -----------------------------------------------------------------------

    @Test
    void readVaultKeyForProvider_claudeCode_readsClaudeOauthTokenKey() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        setField(producer, "vault", mockVault(Map.of(VaultConstants.CLAUDE_CODE_OAUTH_TOKEN_KEY, "cc-oauth")));

        assertEquals(
                "cc-oauth",
                invokePrivate(
                        producer,
                        "readVaultKeyForProvider",
                        new Class<?>[] {String.class},
                        ModelDefaults.PROVIDER_CLAUDE_CODE));
    }

    @Test
    void readVaultKeyForProvider_codex_readsCodexOauthTokenKey() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        setField(producer, "vault", mockVault(Map.of(VaultConstants.CODEX_OAUTH_TOKEN_KEY, "codex-oauth")));

        assertEquals(
                "codex-oauth",
                invokePrivate(
                        producer,
                        "readVaultKeyForProvider",
                        new Class<?>[] {String.class},
                        ModelDefaults.PROVIDER_CODEX));
    }

    @Test
    void readVaultKeyForProvider_agy_readsAgyOauthTokenKey() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        setField(producer, "vault", mockVault(Map.of(VaultConstants.AGY_OAUTH_TOKEN_KEY, "agy-oauth")));

        assertEquals(
                "agy-oauth",
                invokePrivate(
                        producer,
                        "readVaultKeyForProvider",
                        new Class<?>[] {String.class},
                        ModelDefaults.PROVIDER_AGY));
    }

    @Test
    void readVaultKeyForProvider_kimchi_readsKimchiOauthTokenKey() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        setField(producer, "vault", mockVault(Map.of(VaultConstants.KIMCHI_OAUTH_TOKEN_KEY, "kimchi-oauth")));

        assertEquals(
                "kimchi-oauth",
                invokePrivate(
                        producer,
                        "readVaultKeyForProvider",
                        new Class<?>[] {String.class},
                        ModelDefaults.PROVIDER_KIMCHI));
    }

    @Test
    void readVaultKeyForProvider_defaultArm_readsTheGenericLlmApiKey() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        setField(producer, "vault", mockVault(Map.of(VaultConstants.LLM_API_KEY, "sk-generic")));

        assertEquals(
                "sk-generic",
                invokePrivate(
                        producer,
                        "readVaultKeyForProvider",
                        new Class<?>[] {String.class},
                        ModelDefaults.PROVIDER_GROQ));
    }

    @Test
    void readVaultKeyForProvider_isCaseInsensitive() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        setField(producer, "vault", mockVault(Map.of(VaultConstants.CLAUDE_CODE_OAUTH_TOKEN_KEY, "cc-oauth")));

        assertEquals(
                "cc-oauth",
                invokePrivate(producer, "readVaultKeyForProvider", new Class<?>[] {String.class}, "CLAUDE-CODE"));
    }

    // -----------------------------------------------------------------------
    // resolveVaultPriorityConfig() — vault-priority branch of chatModel()
    // -----------------------------------------------------------------------

    @Test
    void resolveVaultPriorityConfig_noVaultProvider_fallsBackToTheProviderFieldWithNulls() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        setField(producer, "provider", "groq");
        setField(producer, "vault", mockVault(Map.of()));

        Object cfg = invokePrivate(producer, "resolveVaultPriorityConfig", new Class<?>[0]);
        assertEquals("groq", recordAccessor(cfg, "provider"));
        assertNull(recordAccessor(cfg, "key"));
        assertNull(recordAccessor(cfg, "model"));
        assertNull(recordAccessor(cfg, "baseUrl"));
    }

    @Test
    void resolveVaultPriorityConfig_blankVaultProvider_fallsBackToTheProviderField() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        setField(producer, "provider", "groq");
        setField(producer, "vault", mockVault(Map.of(VaultConstants.LLM_PROVIDER_KEY, "   ")));

        Object cfg = invokePrivate(producer, "resolveVaultPriorityConfig", new Class<?>[0]);
        assertEquals("groq", recordAccessor(cfg, "provider"));
        assertNull(recordAccessor(cfg, "key"));
    }

    @Test
    void resolveVaultPriorityConfig_vaultProviderWinsAndCarriesModelAndBaseUrl() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        setField(producer, "provider", "groq");
        setField(
                producer,
                "vault",
                mockVault(Map.of(
                        VaultConstants.LLM_PROVIDER_KEY, "  openai  ",
                        VaultConstants.LLM_API_KEY, "sk-vault",
                        VaultConstants.LLM_MODEL_KEY, "gpt-4o-mini",
                        VaultConstants.LLM_BASE_URL_KEY, "https://vault.example/v1")));

        Object cfg = invokePrivate(producer, "resolveVaultPriorityConfig", new Class<?>[0]);
        assertEquals("openai", recordAccessor(cfg, "provider"));
        assertEquals("sk-vault", recordAccessor(cfg, "key"));
        assertEquals("gpt-4o-mini", recordAccessor(cfg, "model"));
        assertEquals("https://vault.example/v1", recordAccessor(cfg, "baseUrl"));
    }

    @Test
    void resolveVaultPriorityConfig_vaultProviderWithoutCredentials_leavesKeyModelAndBaseUrlNull() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        setField(producer, "provider", "groq");
        setField(producer, "vault", mockVault(Map.of(VaultConstants.LLM_PROVIDER_KEY, "openai")));

        Object cfg = invokePrivate(producer, "resolveVaultPriorityConfig", new Class<?>[0]);
        assertEquals("openai", recordAccessor(cfg, "provider"));
        assertNull(recordAccessor(cfg, "key"));
        assertNull(recordAccessor(cfg, "model"));
        assertNull(recordAccessor(cfg, "baseUrl"));
    }

    // -----------------------------------------------------------------------
    // resolveStandardVaultConfig() — non-vault-priority branch
    // -----------------------------------------------------------------------

    @Test
    void resolveStandardVaultConfig_keyPresent_keepsTheProviderFieldAndReadsTheTriple() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        setField(producer, "provider", "groq");
        setField(
                producer,
                "vault",
                mockVault(Map.of(
                        VaultConstants.LLM_API_KEY, "sk-std",
                        VaultConstants.LLM_MODEL_KEY, "llama-3.1-70b",
                        VaultConstants.LLM_BASE_URL_KEY, "https://std.example/v1")));

        Object cfg = invokePrivate(producer, "resolveStandardVaultConfig", new Class<?>[0]);
        assertEquals("groq", recordAccessor(cfg, "provider"));
        assertEquals("sk-std", recordAccessor(cfg, "key"));
        assertEquals("llama-3.1-70b", recordAccessor(cfg, "model"));
        assertEquals("https://std.example/v1", recordAccessor(cfg, "baseUrl"));
    }

    @Test
    void resolveStandardVaultConfig_keyAbsentWithKeylessVaultProvider_adoptsTheVaultProvider() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        setField(producer, "provider", "groq");
        setField(
                producer,
                "vault",
                mockVault(Map.of(VaultConstants.LLM_PROVIDER_KEY, "ollama", VaultConstants.LLM_MODEL_KEY, "llama3")));

        Object cfg = invokePrivate(producer, "resolveStandardVaultConfig", new Class<?>[0]);
        assertEquals("ollama", recordAccessor(cfg, "provider"));
        assertNull(recordAccessor(cfg, "key"));
        assertEquals("llama3", recordAccessor(cfg, "model"));
    }

    @Test
    void resolveStandardVaultConfig_keyAbsentWithHostedVaultProvider_doesNotAdoptIt() throws Exception {
        // Only ollama / omlx / llamacpp are adopted; a hosted provider without a key stays on the field value.
        ChatModelProducer producer = new ChatModelProducer();
        setField(producer, "provider", "groq");
        setField(
                producer,
                "vault",
                mockVault(Map.of(VaultConstants.LLM_PROVIDER_KEY, "openai", VaultConstants.LLM_MODEL_KEY, "gpt-4o")));

        Object cfg = invokePrivate(producer, "resolveStandardVaultConfig", new Class<?>[0]);
        assertEquals("groq", recordAccessor(cfg, "provider"));
        assertEquals("gpt-4o", recordAccessor(cfg, "model"));
    }

    @Test
    void resolveStandardVaultConfig_keyAbsentAndNoVaultProvider_keepsTheProviderField() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        setField(producer, "provider", "groq");
        setField(producer, "vault", mockVault(Map.of(VaultConstants.LLM_MODEL_KEY, "llama3")));

        Object cfg = invokePrivate(producer, "resolveStandardVaultConfig", new Class<?>[0]);
        assertEquals("groq", recordAccessor(cfg, "provider"));
        assertNull(recordAccessor(cfg, "key"));
    }

    // -----------------------------------------------------------------------
    // chatModel() — the three terminal shapes of the produced bean
    // -----------------------------------------------------------------------

    @Test
    void chatModel_keyModelAndCustomBaseUrl_delegatesToTheCreationPortAdapter() throws Exception {
        SecretVault vault = mockVault(Map.of(
                VaultConstants.LLM_API_KEY, "sk-live",
                VaultConstants.LLM_MODEL_KEY, "llama-3.1-70b",
                VaultConstants.LLM_BASE_URL_KEY, "https://custom.example/v1"));
        ChatModelCreationPort port = mock(ChatModelCreationPort.class);
        ChatModel expected = mock(ChatModel.class);
        when(port.createForAdapter(any(), any(), any(), any(), any())).thenReturn(expected);
        AgentLlmConfig cfg = new AgentLlmConfig();
        AgentChatModelProvider factory = stubFactory(ModelDefaults.PROVIDER_GROQ, StructuredOutputMode.NATIVE);

        ChatModelProducer producer =
                buildProducer(ModelDefaults.PROVIDER_GROQ, false, vault, beanManagerResolving(port), cfg, factory);

        assertSame(expected, producer.chatModel());
        verify(port)
                .createForAdapter(
                        eq(ModelDefaults.PROVIDER_GROQ),
                        eq("https://custom.example/v1"),
                        eq("sk-live"),
                        eq("llama-3.1-70b"),
                        same(cfg));
        verify(factory, never()).create();
        verify(factory, never()).create(anyString());
    }

    @Test
    void chatModel_blankVaultBaseUrl_fallsBackToTheProviderDefaultBaseUrl() throws Exception {
        SecretVault vault = mockVault(Map.of(
                VaultConstants.LLM_API_KEY, "sk-live",
                VaultConstants.LLM_MODEL_KEY, "llama-3.1-70b",
                VaultConstants.LLM_BASE_URL_KEY, "   "));
        ChatModelCreationPort port = mock(ChatModelCreationPort.class);
        when(port.createForAdapter(any(), any(), any(), any(), any())).thenReturn(mock(ChatModel.class));
        AgentLlmConfig cfg = new AgentLlmConfig();

        ChatModelProducer producer = buildProducer(
                ModelDefaults.PROVIDER_GROQ,
                false,
                vault,
                beanManagerResolving(port),
                cfg,
                stubFactory(ModelDefaults.PROVIDER_GROQ, StructuredOutputMode.NATIVE));

        producer.chatModel();
        verify(port)
                .createForAdapter(
                        eq(ModelDefaults.PROVIDER_GROQ),
                        eq("https://api.groq.com/openai/v1"),
                        eq("sk-live"),
                        eq("llama-3.1-70b"),
                        same(cfg));
    }

    @Test
    void chatModel_vaultPriorityUsesTheVaultProviderAndItsOwnDefaultBaseUrl() throws Exception {
        // Only an "openai" factory is registered, so resolving "groq" would throw: this
        // discriminates the vault-priority path from the provider-field path.
        SecretVault vault = mockVault(Map.of(
                VaultConstants.LLM_PROVIDER_KEY, "openai",
                VaultConstants.LLM_API_KEY, "sk-vault",
                VaultConstants.LLM_MODEL_KEY, "gpt-4o-mini"));
        ChatModelCreationPort port = mock(ChatModelCreationPort.class);
        when(port.createForAdapter(any(), any(), any(), any(), any())).thenReturn(mock(ChatModel.class));
        AgentLlmConfig cfg = new AgentLlmConfig();

        ChatModelProducer producer = buildProducer(
                ModelDefaults.PROVIDER_GROQ,
                true,
                vault,
                beanManagerResolving(port),
                cfg,
                stubFactory(ModelDefaults.PROVIDER_OPENAI, StructuredOutputMode.NATIVE));

        producer.chatModel();
        verify(port)
                .createForAdapter(
                        eq(ModelDefaults.PROVIDER_OPENAI),
                        eq("https://api.openai.com/v1"),
                        eq("sk-vault"),
                        eq("gpt-4o-mini"),
                        same(cfg));
    }

    @Test
    void chatModel_vaultPriorityWithClaudeCodeReadsTheOauthTokenNotTheGenericApiKey() throws Exception {
        SecretVault vault = mockVault(Map.of(
                VaultConstants.LLM_PROVIDER_KEY, ModelDefaults.PROVIDER_CLAUDE_CODE,
                VaultConstants.CLAUDE_CODE_OAUTH_TOKEN_KEY, "cc-oauth",
                VaultConstants.LLM_API_KEY, "sk-must-not-be-used",
                VaultConstants.LLM_MODEL_KEY, "claude-sonnet-4"));
        ChatModelCreationPort port = mock(ChatModelCreationPort.class);
        when(port.createForAdapter(any(), any(), any(), any(), any())).thenReturn(mock(ChatModel.class));

        ChatModelProducer producer = buildProducer(
                ModelDefaults.PROVIDER_GROQ,
                true,
                vault,
                beanManagerResolving(port),
                new AgentLlmConfig(),
                stubFactory(ModelDefaults.PROVIDER_CLAUDE_CODE, StructuredOutputMode.NATIVE));

        producer.chatModel();
        verify(port)
                .createForAdapter(
                        eq(ModelDefaults.PROVIDER_CLAUDE_CODE),
                        eq("https://api.anthropic.com"),
                        eq("cc-oauth"),
                        eq("claude-sonnet-4"),
                        any());
    }

    @Test
    void chatModel_keylessWithSupportingAdapter_usesThePortWithTheNotNeededPlaceholderKey() throws Exception {
        SecretVault vault = mockVault(Map.of(VaultConstants.LLM_MODEL_KEY, "llama3"));
        ChatModelCreationPort port = mock(ChatModelCreationPort.class);
        ChatModel expected = mock(ChatModel.class);
        when(port.supportsAdapter(ModelDefaults.PROVIDER_OLLAMA)).thenReturn(true);
        when(port.createForAdapter(any(), any(), any(), any(), any())).thenReturn(expected);
        AgentLlmConfig cfg = new AgentLlmConfig();
        AgentChatModelProvider factory = stubFactory(ModelDefaults.PROVIDER_OLLAMA, StructuredOutputMode.NATIVE);

        ChatModelProducer producer =
                buildProducer(ModelDefaults.PROVIDER_OLLAMA, false, vault, beanManagerResolving(port), cfg, factory);

        assertSame(expected, producer.chatModel());
        verify(port)
                .createForAdapter(
                        eq(ModelDefaults.PROVIDER_OLLAMA),
                        eq("http://localhost:11434"),
                        eq("not-needed"),
                        eq("llama3"),
                        same(cfg));
        verify(factory, never()).create(anyString());
    }

    @Test
    void chatModel_keylessWithUnsupportingAdapter_fallsBackToTheFactoryWithTheModelName() throws Exception {
        SecretVault vault = mockVault(Map.of(VaultConstants.LLM_MODEL_KEY, "llama3"));
        ChatModelCreationPort port = mock(ChatModelCreationPort.class);
        when(port.supportsAdapter(anyString())).thenReturn(false);
        AgentChatModelProvider factory = stubFactory(ModelDefaults.PROVIDER_OLLAMA, StructuredOutputMode.NATIVE);

        ChatModelProducer producer = buildProducer(
                ModelDefaults.PROVIDER_OLLAMA, false, vault, beanManagerResolving(port), new AgentLlmConfig(), factory);

        producer.chatModel();
        verify(factory).create("llama3");
        verify(port, never()).createForAdapter(any(), any(), any(), any(), any());
    }

    @Test
    void chatModel_keylessWithNoCreationPortBean_fallsBackToTheFactory() throws Exception {
        SecretVault vault = mockVault(Map.of(VaultConstants.LLM_MODEL_KEY, "llama3"));
        AgentChatModelProvider factory = stubFactory(ModelDefaults.PROVIDER_OLLAMA, StructuredOutputMode.NATIVE);

        ChatModelProducer producer = buildProducer(
                ModelDefaults.PROVIDER_OLLAMA, false, vault, beanManagerWithoutBeans(), new AgentLlmConfig(), factory);

        producer.chatModel();
        verify(factory).create("llama3");
    }

    @Test
    void chatModel_baseUrlOnlyWithoutModel_reachesTheFactoryBareCreate() throws Exception {
        // hasText(model) is false, so createKeylessVaultModel cannot take the adapter path even
        // though a base URL is present; it degrades to the no-argument factory call.
        SecretVault vault = mockVault(Map.of(VaultConstants.LLM_BASE_URL_KEY, "https://custom.example/v1"));
        ChatModelCreationPort port = mock(ChatModelCreationPort.class);
        when(port.supportsAdapter(anyString())).thenReturn(true);
        AgentChatModelProvider factory = stubFactory(ModelDefaults.PROVIDER_GROQ, StructuredOutputMode.NATIVE);

        ChatModelProducer producer = buildProducer(
                ModelDefaults.PROVIDER_GROQ, false, vault, beanManagerResolving(port), new AgentLlmConfig(), factory);

        producer.chatModel();
        verify(factory).create();
        verify(factory, never()).create(anyString());
        verify(port, never()).createForAdapter(any(), any(), any(), any(), any());
    }

    @Test
    void chatModel_emptyVault_reachesTheFactoryBareCreate() throws Exception {
        // The provider default base URL is computed but never consulted for the branch decision:
        // the guard tests vc.baseUrl() (the raw vault value), which is null here.
        AgentChatModelProvider factory = stubFactory(ModelDefaults.PROVIDER_GROQ, StructuredOutputMode.NATIVE);

        ChatModelProducer producer = buildProducer(
                ModelDefaults.PROVIDER_GROQ,
                false,
                mockVault(Map.of()),
                beanManagerWithoutBeans(),
                new AgentLlmConfig(),
                factory);

        producer.chatModel();
        verify(factory).create();
        verify(factory, never()).create(anyString());
    }

    @Test
    void chatModel_genericOpenAiHasABlankDefaultBaseUrlSoItDegradesToTheKeylessAdapter() throws Exception {
        // PROVIDER_BASE_URLS maps generic-openai to "", so key+model alone can never satisfy the
        // full-configuration guard; the call lands in createKeylessVaultModel with an empty base URL.
        SecretVault vault =
                mockVault(Map.of(VaultConstants.LLM_API_KEY, "sk-live", VaultConstants.LLM_MODEL_KEY, "some-model"));
        ChatModelCreationPort port = mock(ChatModelCreationPort.class);
        when(port.supportsAdapter(ModelDefaults.PROVIDER_GENERIC_OPENAI)).thenReturn(true);
        when(port.createForAdapter(any(), any(), any(), any(), any())).thenReturn(mock(ChatModel.class));
        AgentLlmConfig cfg = new AgentLlmConfig();

        ChatModelProducer producer = buildProducer(
                ModelDefaults.PROVIDER_GENERIC_OPENAI,
                false,
                vault,
                beanManagerResolving(port),
                cfg,
                stubFactory(ModelDefaults.PROVIDER_GENERIC_OPENAI, StructuredOutputMode.NATIVE));

        producer.chatModel();
        verify(port)
                .createForAdapter(
                        eq(ModelDefaults.PROVIDER_GENERIC_OPENAI),
                        eq(""),
                        eq("not-needed"),
                        eq("some-model"),
                        same(cfg));
    }

    @Test
    void chatModel_standardPathAdoptsAKeylessVaultProviderWhenTheFieldProviderHasNoKey() throws Exception {
        // Only an "ollama" factory is registered: if the switch to the vault provider did not
        // happen, findProvider("groq") would throw instead.
        SecretVault vault =
                mockVault(Map.of(VaultConstants.LLM_PROVIDER_KEY, "ollama", VaultConstants.LLM_MODEL_KEY, "llama3"));
        AgentChatModelProvider factory = stubFactory(ModelDefaults.PROVIDER_OLLAMA, StructuredOutputMode.NATIVE);

        ChatModelProducer producer = buildProducer(
                ModelDefaults.PROVIDER_GROQ, false, vault, beanManagerWithoutBeans(), new AgentLlmConfig(), factory);

        producer.chatModel();
        verify(factory).create("llama3");
    }

    @Test
    void chatModel_standardPathIgnoresAHostedVaultProviderWhenTheFieldProviderHasNoKey() throws Exception {
        // Only a "groq" factory is registered: adopting the hosted vault provider would throw.
        SecretVault vault =
                mockVault(Map.of(VaultConstants.LLM_PROVIDER_KEY, "openai", VaultConstants.LLM_MODEL_KEY, "gpt-4o"));
        AgentChatModelProvider factory = stubFactory(ModelDefaults.PROVIDER_GROQ, StructuredOutputMode.NATIVE);

        ChatModelProducer producer = buildProducer(
                ModelDefaults.PROVIDER_GROQ, false, vault, beanManagerWithoutBeans(), new AgentLlmConfig(), factory);

        producer.chatModel();
        verify(factory).create("gpt-4o");
    }

    // -----------------------------------------------------------------------
    // structuredOutputMode() — the @Identifier("agent-output-mode") producer
    // -----------------------------------------------------------------------

    @Test
    void structuredOutputMode_withoutVaultPriority_readsTheProviderField() throws Exception {
        AgentChatModelProvider factory = stubFactory(ModelDefaults.PROVIDER_GROQ, StructuredOutputMode.LENIENT);
        ChatModelProducer producer = buildProducer(
                ModelDefaults.PROVIDER_GROQ,
                false,
                mockVault(Map.of()),
                beanManagerWithoutBeans(),
                new AgentLlmConfig(),
                factory);

        assertEquals(StructuredOutputMode.LENIENT, producer.structuredOutputMode());
    }

    @Test
    void structuredOutputMode_withVaultPriority_readsTheVaultProvider() throws Exception {
        SecretVault vault = mockVault(Map.of(VaultConstants.LLM_PROVIDER_KEY, "openai"));
        AgentChatModelProvider factory =
                stubFactory(ModelDefaults.PROVIDER_OPENAI, StructuredOutputMode.PROMPT_DIRECTIVE);
        ChatModelProducer producer = buildProducer(
                ModelDefaults.PROVIDER_GROQ, true, vault, beanManagerWithoutBeans(), new AgentLlmConfig(), factory);

        assertEquals(StructuredOutputMode.PROMPT_DIRECTIVE, producer.structuredOutputMode());
    }

    @Test
    void structuredOutputMode_withVaultPriorityAndNoVaultProvider_fallsBackToTheProviderField() throws Exception {
        AgentChatModelProvider factory = stubFactory(ModelDefaults.PROVIDER_GROQ, StructuredOutputMode.NATIVE);
        ChatModelProducer producer = buildProducer(
                ModelDefaults.PROVIDER_GROQ,
                true,
                mockVault(Map.of()),
                beanManagerWithoutBeans(),
                new AgentLlmConfig(),
                factory);

        assertEquals(StructuredOutputMode.NATIVE, producer.structuredOutputMode());
    }

    // -----------------------------------------------------------------------
    // Pinned asymmetries in resolveProvider() (documented, not fixed)
    // -----------------------------------------------------------------------

    @Test
    void resolveProvider_vaultPriorityLeavesTheVaultKeyUnwipedUnlikeEveryOtherReader() throws Exception {
        // readVaultString / resolveVaultPriorityConfig / resolveStandardVaultConfig all use
        // try-with-resources; resolveProvider() alone does not, so the key material is never
        // zeroed. This test pins the current behaviour rather than asserting it is correct.
        VaultKey key = new VaultKey("openai".getBytes(StandardCharsets.UTF_8));
        SecretVault vault = mock(SecretVault.class);
        when(vault.retrieveSystemKey(VaultConstants.LLM_PROVIDER_KEY)).thenReturn(Optional.of(key));

        ChatModelProducer producer = new ChatModelProducer();
        setField(producer, "vaultPriority", true);
        setField(producer, "provider", "groq");
        setField(producer, "vault", vault);

        assertEquals("openai", invokePrivate(producer, "resolveProvider", new Class<?>[0]));
        assertFalse(key.isDestroyed(), "resolveProvider does not close the VaultKey it reads");
    }

    @Test
    void resolveProvider_vaultPriorityReturnsTheRawUntrimmedValue() throws Exception {
        // Unlike resolveVaultPriorityConfig(), which trims, resolveProvider() returns the vault
        // value verbatim. Downstream findProvider() cleans it, so this is latent, not live.
        ChatModelProducer producer = new ChatModelProducer();
        setField(producer, "vaultPriority", true);
        setField(producer, "provider", "groq");
        setField(producer, "vault", mockVault(Map.of(VaultConstants.LLM_PROVIDER_KEY, "  openai  ")));

        assertEquals("  openai  ", invokePrivate(producer, "resolveProvider", new Class<?>[0]));
    }

    // -----------------------------------------------------------------------
    // Residual branch coverage: the arms a single-element / single-arm fixture
    // never reaches.
    // -----------------------------------------------------------------------

    @Test
    void findProvider_skipsANonMatchingFactoryBeforeReturningTheMatch() throws Exception {
        // A one-element instance returns from the first iteration, so the loop back edge is never
        // executed. Matching on the second element is what actually exercises the skip path.
        AgentChatModelProvider openai = stubFactory(ModelDefaults.PROVIDER_OPENAI, StructuredOutputMode.NATIVE);
        AgentChatModelProvider groq = stubFactory(ModelDefaults.PROVIDER_GROQ, StructuredOutputMode.NATIVE);
        ChatModelProducer producer = buildProducer(
                ModelDefaults.PROVIDER_GROQ,
                false,
                mockVault(Map.of()),
                beanManagerWithoutBeans(),
                new AgentLlmConfig(),
                openai,
                groq);

        Object result =
                invokePrivate(producer, "findProvider", new Class<?>[] {String.class}, ModelDefaults.PROVIDER_GROQ);
        assertSame(groq, result);
    }

    @Test
    void chatModel_keyWithoutModel_fallsThroughToTheBareFactory() throws Exception {
        // The key is present but the model is not, so the three-way guard stops at its second term.
        SecretVault vault = mockVault(Map.of(VaultConstants.LLM_API_KEY, "sk-live"));
        AgentChatModelProvider factory = stubFactory(ModelDefaults.PROVIDER_GROQ, StructuredOutputMode.NATIVE);

        ChatModelProducer producer = buildProducer(
                ModelDefaults.PROVIDER_GROQ, false, vault, beanManagerWithoutBeans(), new AgentLlmConfig(), factory);

        producer.chatModel();
        verify(factory).create();
        verify(factory, never()).create(anyString());
    }

    @Test
    void chatModel_whitespaceOnlyKey_isTreatedAsAbsent() throws Exception {
        // readVaultString trims to "", and hasText("") is false via the isBlank() arm.
        SecretVault vault = mockVault(Map.of(VaultConstants.LLM_API_KEY, "   "));
        AgentChatModelProvider factory = stubFactory(ModelDefaults.PROVIDER_GROQ, StructuredOutputMode.NATIVE);

        ChatModelProducer producer = buildProducer(
                ModelDefaults.PROVIDER_GROQ, false, vault, beanManagerWithoutBeans(), new AgentLlmConfig(), factory);

        producer.chatModel();
        verify(factory).create();
    }

    @Test
    void resolveStandardVaultConfig_keyAbsentWithOmlxVaultProvider_adoptsTheVaultProvider() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        setField(producer, "provider", "groq");
        setField(producer, "vault", mockVault(Map.of(VaultConstants.LLM_PROVIDER_KEY, "omlx")));

        Object cfg = invokePrivate(producer, "resolveStandardVaultConfig", new Class<?>[0]);
        assertEquals("omlx", recordAccessor(cfg, "provider"));
    }

    @Test
    void resolveStandardVaultConfig_keyAbsentWithLlamacppVaultProvider_adoptsTheVaultProvider() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        setField(producer, "provider", "groq");
        setField(producer, "vault", mockVault(Map.of(VaultConstants.LLM_PROVIDER_KEY, "llamacpp")));

        Object cfg = invokePrivate(producer, "resolveStandardVaultConfig", new Class<?>[0]);
        assertEquals("llamacpp", recordAccessor(cfg, "provider"));
    }

    @Test
    void resolveProvider_vaultPriorityWithABlankValue_fallsBackToTheProviderField() throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        setField(producer, "vaultPriority", true);
        setField(producer, "provider", "groq");
        setField(producer, "vault", mockVault(Map.of(VaultConstants.LLM_PROVIDER_KEY, "   ")));

        assertEquals("groq", invokePrivate(producer, "resolveProvider", new Class<?>[0]));
    }

    @Test
    void resolveVaultPriorityConfig_vaultReadFailureStillWipesTheProviderKeyAndPropagates() throws Exception {
        // Covers the exceptional edge of the try-with-resources: a failure while reading the
        // credentials must still zero the provider key before the exception escapes.
        VaultKey providerKey = new VaultKey("openai".getBytes(StandardCharsets.UTF_8));
        SecretVault vault = mock(SecretVault.class);
        when(vault.retrieveSystemKey(VaultConstants.LLM_PROVIDER_KEY)).thenReturn(Optional.of(providerKey));
        when(vault.retrieveSystemKey(VaultConstants.LLM_API_KEY))
                .thenThrow(new IllegalStateException("vault unavailable"));

        ChatModelProducer producer = new ChatModelProducer();
        setField(producer, "vault", vault);

        InvocationTargetException ite = assertThrows(
                InvocationTargetException.class,
                () -> invokePrivate(producer, "resolveVaultPriorityConfig", new Class<?>[0]));
        assertInstanceOf(IllegalStateException.class, ite.getCause());
        assertTrue(
                providerKey.isDestroyed(), "try-with-resources must still wipe the provider key on the failure path");
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    /**
     * Invokes a private instance method on {@link ChatModelProducer} and returns the raw result.
     */
    private Object invokePrivate(Object target, String name, Class<?>[] paramTypes, Object... args) throws Exception {
        Method m = ChatModelProducer.class.getDeclaredMethod(name, paramTypes);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    /**
     * Reads a component of the private {@code ChatModelProducer.VaultLlmConfig} record.
     */
    private String recordAccessor(Object config, String accessor) throws Exception {
        Method m = config.getClass().getDeclaredMethod(accessor);
        m.setAccessible(true);
        return (String) m.invoke(config);
    }

    /**
     * Builds a {@link SecretVault} whose {@code retrieveSystemKey} answers from {@code systemKeys}.
     * A fresh {@link VaultKey} is produced per call because the production code closes what it reads.
     */
    private SecretVault mockVault(Map<String, String> systemKeys) {
        SecretVault vault = mock(SecretVault.class);
        when(vault.retrieveSystemKey(any())).thenAnswer(invocation -> {
            String value = systemKeys.get(invocation.getArgument(0, String.class));
            return value == null ? Optional.empty() : Optional.of(new VaultKey(value.getBytes(StandardCharsets.UTF_8)));
        });
        return vault;
    }

    @SuppressWarnings("unchecked")
    private BeanManager beanManagerResolving(ChatModelCreationPort port) {
        BeanManager beanManager = mock(BeanManager.class);
        Bean<ChatModelCreationPort> bean = mock(Bean.class);
        doReturn(ChatModelCreationPort.class).when(bean).getBeanClass();
        doReturn(Set.of(ChatModelCreationPort.class)).when(bean).getTypes();
        when(beanManager.getBeans(Object.class)).thenReturn(Set.of(bean));
        doReturn(bean).when(beanManager).resolve(anySet());
        when(beanManager.getReference(eq(bean), eq(ChatModelCreationPort.class), any()))
                .thenReturn(port);
        return beanManager;
    }

    private BeanManager beanManagerWithoutBeans() {
        BeanManager beanManager = mock(BeanManager.class);
        when(beanManager.getBeans(Object.class)).thenReturn(Collections.emptySet());
        return beanManager;
    }

    private AgentChatModelProvider stubFactory(String providerName, StructuredOutputMode mode) {
        AgentChatModelProvider factory = mock(AgentChatModelProvider.class);
        when(factory.providerName()).thenReturn(providerName);
        when(factory.structuredOutputMode()).thenReturn(mode);
        when(factory.create()).thenReturn(mock(ChatModel.class));
        when(factory.create(anyString())).thenReturn(mock(ChatModel.class));
        return factory;
    }

    private ChatModelProducer buildProducer(
            String provider,
            boolean vaultPriority,
            SecretVault vault,
            BeanManager beanManager,
            AgentLlmConfig agentConfig,
            AgentChatModelProvider... factories)
            throws Exception {
        ChatModelProducer producer = new ChatModelProducer();
        setField(producer, "provider", provider);
        setField(producer, "vaultPriority", vaultPriority);
        setField(producer, "vault", vault);
        setField(producer, "beanManager", beanManager);
        setField(producer, "agentConfig", agentConfig);
        setField(producer, "providers", createInstanceWith(factories));
        return producer;
    }

    @SuppressWarnings("unchecked")
    private Instance<AgentChatModelProvider> createEmptyInstance() {
        return (Instance<AgentChatModelProvider>) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Instance.class}, (proxy, method, args) -> {
                    if ("iterator".equals(method.getName())) {
                        return Collections.emptyIterator();
                    }
                    return null;
                });
    }

    @SuppressWarnings("unchecked")
    private Instance<AgentChatModelProvider> createInstanceWith(AgentChatModelProvider... providers) {
        return (Instance<AgentChatModelProvider>) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Instance.class}, (proxy, method, args) -> {
                    if ("iterator".equals(method.getName())) {
                        return List.of(providers).iterator();
                    }
                    return null;
                });
    }
}
