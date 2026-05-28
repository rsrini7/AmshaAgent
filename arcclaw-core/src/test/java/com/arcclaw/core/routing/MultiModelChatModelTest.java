package com.arcclaw.core.routing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;

import com.arcclaw.core.agent.reasoning.error.LlmAuthenticationException;
import com.arcclaw.core.agent.reasoning.error.LlmRateLimitException;
import com.arcclaw.core.agent.reasoning.error.LlmServiceUnavailableException;

import org.junit.jupiter.api.Test;

class MultiModelChatModelTest {

    private static ChatRequest request() {
        return ChatRequest.builder().messages(List.of(UserMessage.from("hi"))).build();
    }

    private static ChatResponse response() {
        return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
    }

    private static MultiModelChatModel.ModelEntry entry(String label, ChatModel model) {
        return new MultiModelChatModel.ModelEntry(label, model);
    }

    @Test
    void singleEntry_alwaysServesFromIt() {
        ChatModel m = mock(ChatModel.class);
        when(m.chat(any(ChatRequest.class))).thenReturn(response());

        MultiModelChatModel multi = new MultiModelChatModel("test", List.of(entry("m1", m)), 5);

        multi.chat(request());
        multi.chat(request());

        verify(m, times(2)).chat(any(ChatRequest.class));
    }

    @Test
    void twoEntries_roundRobin() {
        ChatModel m1 = mock(ChatModel.class);
        ChatModel m2 = mock(ChatModel.class);
        when(m1.chat(any(ChatRequest.class))).thenReturn(response());
        when(m2.chat(any(ChatRequest.class))).thenReturn(response());

        MultiModelChatModel multi = new MultiModelChatModel("test", List.of(entry("m1", m1), entry("m2", m2)), 5);

        multi.chat(request());
        multi.chat(request());

        verify(m1, times(1)).chat(any(ChatRequest.class));
        verify(m2, times(1)).chat(any(ChatRequest.class));
    }

    @Test
    void rateLimited_rotatesToNextModel() {
        ChatModel m1 = mock(ChatModel.class);
        ChatModel m2 = mock(ChatModel.class);
        when(m1.chat(any(ChatRequest.class)))
                .thenThrow(new LlmRateLimitException("rate limited", new RuntimeException()));
        when(m2.chat(any(ChatRequest.class))).thenReturn(response());

        MultiModelChatModel multi = new MultiModelChatModel("test", List.of(entry("m1", m1), entry("m2", m2)), 5);

        ChatResponse result = multi.chat(request());
        assertNotNull(result);

        // At least one model served — round-robin may start on either
        verify(m2, atLeastOnce()).chat(any(ChatRequest.class));
    }

    @Test
    void authError_permanentlyDisables() {
        ChatModel m1 = mock(ChatModel.class);
        ChatModel m2 = mock(ChatModel.class);
        when(m1.chat(any(ChatRequest.class)))
                .thenThrow(new LlmAuthenticationException("invalid key", new RuntimeException()));
        when(m2.chat(any(ChatRequest.class))).thenReturn(response());

        MultiModelChatModel multi = new MultiModelChatModel("test", List.of(entry("m1", m1), entry("m2", m2)), 5);

        // First call: m1 disabled, m2 serves
        multi.chat(request());
        verify(m2, times(1)).chat(any(ChatRequest.class));

        // Second call: m1 still disabled, m2 serves again
        multi.chat(request());
        verify(m2, times(2)).chat(any(ChatRequest.class));

        // m1 never called again
        verify(m1, times(1)).chat(any(ChatRequest.class));
    }

    @Test
    void allDisabled_throws() {
        ChatModel m1 = mock(ChatModel.class);
        ChatModel m2 = mock(ChatModel.class);
        when(m1.chat(any(ChatRequest.class)))
                .thenThrow(new LlmAuthenticationException("invalid", new RuntimeException()));
        when(m2.chat(any(ChatRequest.class)))
                .thenThrow(new LlmAuthenticationException("invalid", new RuntimeException()));

        MultiModelChatModel multi = new MultiModelChatModel("test", List.of(entry("m1", m1), entry("m2", m2)), 5);

        assertThrows(RuntimeException.class, () -> multi.chat(request()));
    }

    @Test
    void transientFailure_disablesAfterThreshold() {
        ChatModel m1 = mock(ChatModel.class);
        ChatModel m2 = mock(ChatModel.class);
        when(m1.chat(any(ChatRequest.class)))
                .thenThrow(new LlmServiceUnavailableException("503", new RuntimeException()));
        when(m2.chat(any(ChatRequest.class))).thenReturn(response());

        MultiModelChatModel multi = new MultiModelChatModel("test", List.of(entry("m1", m1), entry("m2", m2)), 5);

        // After enough calls, m1 gets permanently disabled and m2 handles all remaining calls
        for (int i = 0; i < 10; i++) {
            multi.chat(request());
        }

        // m1 was called at most 3 times (then permanently disabled)
        verify(m1, atMost(3)).chat(any(ChatRequest.class));
        // m2 handled the rest
        verify(m2, atLeast(7)).chat(any(ChatRequest.class));
    }

    @Test
    void constructor_emptyEntries_throws() {
        assertThrows(IllegalArgumentException.class, () -> new MultiModelChatModel("test", List.of(), 5));
    }

    @Test
    void constructor_nullEntries_throws() {
        assertThrows(IllegalArgumentException.class, () -> new MultiModelChatModel("test", null, 5));
    }
}
