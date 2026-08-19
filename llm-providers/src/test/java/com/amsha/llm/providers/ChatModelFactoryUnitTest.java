package com.amsha.llm.providers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.Map;

import com.amsha.llm.api.AgentLlmConfig;
import com.amsha.llm.api.StructuredOutputMode;

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
}
