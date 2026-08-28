package com.amsha.llm.providers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.langchain4j.model.openai.OpenAiChatModel;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import com.amsha.llm.api.AgentLlmConfig;
import com.amsha.llm.api.LlmModelConfig;
import com.amsha.llm.api.StructuredOutputMode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChatModelFactoryUnitTest {

    @Test
    void testDeepSeekHelpers() {
        assertTrue(ChatModelFactory.isDeepSeekThinkingModel("deepseek-v3"));
        assertTrue(ChatModelFactory.isDeepSeekThinkingModel("deepseek-r1"));
        assertFalse(ChatModelFactory.isDeepSeekThinkingModel("gpt-4o"));
        assertFalse(ChatModelFactory.isDeepSeekThinkingModel(null));

        Map<String, Object> params = ChatModelFactory.deepSeekWorkaroundParams("deepseek-r1");
        assertFalse(params.isEmpty());

        Map<String, Object> emptyParams = ChatModelFactory.deepSeekWorkaroundParams("gpt-4o");
        assertTrue(emptyParams.isEmpty());
    }

    @Test
    void testParseExtraBody() {
        assertTrue(ChatModelFactory.parseExtraBody("").isEmpty());
        assertTrue(ChatModelFactory.parseExtraBody(null).isEmpty());
        assertTrue(ChatModelFactory.parseExtraBody("invalid json").isEmpty());

        Map<String, Object> valid = ChatModelFactory.parseExtraBody("{\"key\":\"val\"}");
        assertEquals("val", valid.get("key"));
    }

    @Test
    void testMergeCustomParameters() {
        Map<String, Object> auto = Map.of("a", 1);
        Map<String, Object> user = Map.of("b", 2);

        Map<String, Object> merged = ChatModelFactory.mergeCustomParameters(auto, user);
        assertEquals(1, merged.get("a"));
        assertEquals(2, merged.get("b"));

        assertEquals(auto, ChatModelFactory.mergeCustomParameters(auto, null));
        assertEquals(user, ChatModelFactory.mergeCustomParameters(null, user));
        assertTrue(ChatModelFactory.mergeCustomParameters(null, null).isEmpty());
    }

    @Test
    void testOllamaAndMockConfig() {
        AgentLlmConfig config = mock(AgentLlmConfig.class);
        when(config.temperature()).thenReturn(0.1);
        when(config.maxTokens()).thenReturn(2048);
        when(config.timeout()).thenReturn(Duration.ofSeconds(30));
        when(config.maxRetries()).thenReturn(3);
        when(config.logHttp()).thenReturn(false);
        when(config.structuredOutputMode()).thenReturn(StructuredOutputMode.NATIVE);

        assertNotNull(ChatModelFactory.createOllama("http://localhost:11434", "llama3", config));
    }

    @Test
    void testSupportsAdapter() {
        ChatModelFactory factory = new ChatModelFactory();
        assertTrue(factory.supportsAdapter("openai"));
        assertTrue(factory.supportsAdapter("OPENAI"));
        assertTrue(factory.supportsAdapter("gemini"));
        assertTrue(factory.supportsAdapter("claude"));
        assertTrue(factory.supportsAdapter("claude-code"));
        assertTrue(factory.supportsAdapter("agy"));
        assertTrue(factory.supportsAdapter("antigravity"));
        assertTrue(factory.supportsAdapter("kimchi"));
        assertTrue(factory.supportsAdapter("foundry"));
        assertTrue(factory.supportsAdapter("foundrylocal"));
        assertTrue(factory.supportsAdapter("ollama"));
        assertTrue(factory.supportsAdapter("codex"));
        assertTrue(factory.supportsAdapter("groq"));
        assertTrue(factory.supportsAdapter("cerebras"));
        assertTrue(factory.supportsAdapter("sarvam"));
        assertTrue(factory.supportsAdapter("opencode"));
        assertTrue(factory.supportsAdapter("huggingface"));
        assertTrue(factory.supportsAdapter("mistral"));
        assertTrue(factory.supportsAdapter("cloudflare"));
        assertTrue(factory.supportsAdapter("cohere"));
        assertTrue(factory.supportsAdapter("nvidia"));
        assertTrue(factory.supportsAdapter("omlx"));
        assertTrue(factory.supportsAdapter("llamacpp"));
        assertTrue(factory.supportsAdapter("openrouter"));
        assertTrue(factory.supportsAdapter("generic-openai"));
        assertFalse(factory.supportsAdapter("unknown-adapter"));
        assertFalse(factory.supportsAdapter(null));
    }

    @Test
    void testWithOpenRouterHeaders() {
        var builder = OpenAiChatModel.builder().apiKey("test");
        var updated = ChatModelFactory.withOpenRouterHeaders(builder, "https://amsha.ai", "Amsha");
        assertNotNull(updated);
    }

    @Test
    @DisplayName("applyJsonResponseFormat applies JSON response format only when toolless and native")
    void testApplyJsonResponseFormat() {
        var builder1 = OpenAiChatModel.builder().apiKey("test");
        ChatModelFactory.applyJsonResponseFormat(builder1, StructuredOutputMode.NATIVE, true);

        var builder2 = OpenAiChatModel.builder().apiKey("test");
        ChatModelFactory.applyJsonResponseFormat(builder2, StructuredOutputMode.NATIVE, false);

        var builder3 = OpenAiChatModel.builder().apiKey("test");
        ChatModelFactory.applyJsonResponseFormat(
                builder3, StructuredOutputMode.NATIVE, true, "https://headroom.example.com/v1");
        assertNotNull(builder3);
    }

    @Test
    @DisplayName("createFromConfig enforces API key presence")
    void testCreateFromConfig_missingApiKey() {
        LlmModelConfig missingKey = new LlmModelConfig(
                "openai",
                "gpt-4o",
                "https://api.openai.com/v1",
                Optional.empty(),
                Optional.of(4096),
                Optional.of(Duration.ofSeconds(30)),
                Optional.of(3),
                StructuredOutputMode.NATIVE,
                true,
                true);

        assertThrows(IllegalStateException.class, () -> ChatModelFactory.createFromConfig(missingKey));
    }
}
