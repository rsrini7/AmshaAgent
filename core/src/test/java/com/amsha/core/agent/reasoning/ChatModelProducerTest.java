package com.amsha.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.inject.Instance;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Map;

import com.amsha.llm.api.AgentChatModelProvider;
import com.amsha.llm.api.ModelDefaults;

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

    // -----------------------------------------------------------------------
    // Helper methods
    // -----------------------------------------------------------------------

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
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
    private Instance<AgentChatModelProvider> createInstanceWith(AgentChatModelProvider provider) {
        return (Instance<AgentChatModelProvider>) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Instance.class}, (proxy, method, args) -> {
                    if ("iterator".equals(method.getName())) {
                        return Collections.singletonList(provider).iterator();
                    }
                    return null;
                });
    }
}
