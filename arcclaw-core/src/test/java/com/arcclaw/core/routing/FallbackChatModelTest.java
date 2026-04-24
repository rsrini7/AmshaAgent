package com.arcclaw.core.routing;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import com.arcclaw.core.scope.WorkspaceContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
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

        FallbackChatModel model = new FallbackChatModel(primary, fallback, "gemini", "openrouter", 14);

        ChatResponse result = model.chat(request);

        assertSame(fallbackResponse, result);
        verify(primary).chat(request);
        verify(fallback).chat(request);
    }

    @Test
    void testWriteIntentBypassesPreemptiveFallback() throws Exception {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fallback = mock(ChatModel.class);
        
        UserMessage msg = UserMessage.userMessage("test");
        ChatRequest request = ChatRequest.builder()
                .messages(Collections.nCopies(20, msg))
                .build();
        
        ChatResponse primaryResponse = ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage("Primary Answer"))
                .build();
        
        when(primary.chat(any(ChatRequest.class))).thenReturn(primaryResponse);

        FallbackChatModel model = new FallbackChatModel(primary, fallback, "primary", "fallback", 14);

        // Bind IS_WRITE_INTENT=true
        ChatResponse result = ScopedValue.where(WorkspaceContext.IS_WRITE_INTENT, true)
                .call(() -> model.chat(request));

        assertSame(primaryResponse, result);
        verify(primary).chat(request);
        verifyNoInteractions(fallback);
    }

    @Test
    void testCompactTurnAttemptsFallbackFirst() throws Exception {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fallback = mock(ChatModel.class);
        
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(UserMessage.userMessage("test")))
                .build();
        
        ChatResponse fallbackResponse = ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage("Fallback Answer"))
                .build();
        
        when(fallback.chat(any(ChatRequest.class))).thenReturn(fallbackResponse);

        FallbackChatModel model = new FallbackChatModel(primary, fallback, "primary", "fallback", 14);

        // Bind IS_COMPACT=true
        ChatResponse result = ScopedValue.where(WorkspaceContext.IS_COMPACT, true)
                .call(() -> model.chat(request));

        assertSame(fallbackResponse, result);
        verify(fallback).chat(request);
        verifyNoInteractions(primary);
    }

    @Test
    void testLargeHistoryPreemptivelyUsesFallback() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fallback = mock(ChatModel.class);
        
        UserMessage msg = UserMessage.userMessage("test");
        ChatRequest request = ChatRequest.builder()
                .messages(Collections.nCopies(20, msg))
                .build();
        
        ChatResponse fallbackResponse = ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage("Fallback Answer"))
                .build();
        
        when(fallback.chat(any(ChatRequest.class))).thenReturn(fallbackResponse);

        FallbackChatModel model = new FallbackChatModel(primary, fallback, "primary", "fallback", 14);

        // No flags bound
        ChatResponse result = model.chat(request);

        assertSame(fallbackResponse, result);
        verify(fallback).chat(request);
        verifyNoInteractions(primary);
    }
}
