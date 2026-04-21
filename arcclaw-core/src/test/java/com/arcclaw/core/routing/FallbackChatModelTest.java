package com.arcclaw.core.routing;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

class FallbackChatModelTest {

    @Test
    void fallsBackOnGeminiStyleSafetyBlock() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fallback = mock(ChatModel.class);
        ChatRequest request = mock(ChatRequest.class);
        ChatResponse fallbackResponse = mock(ChatResponse.class);

        when(primary.chat(request)).thenThrow(new RuntimeException(
                "Gemini returned an empty response or was blocked by safety filters."));
        when(fallback.chat(request)).thenReturn(fallbackResponse);

        FallbackChatModel model = new FallbackChatModel(primary, fallback, "gemini", "openrouter");

        ChatResponse result = model.chat(request);

        assertSame(fallbackResponse, result);
        verify(primary).chat(request);
        verify(fallback).chat(request);
    }
}
