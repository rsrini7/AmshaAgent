package com.amsha.llm.providers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;

import com.amsha.llm.providers.wrapper.NvidiaMessageSanitizer;

import org.junit.jupiter.api.Test;

class NvidiaMessageSanitizerCoverageTest {

    @Test
    void testNullModelThrows() {
        NvidiaMessageSanitizer sanitizer = new NvidiaMessageSanitizer(null);
        ChatRequest req =
                ChatRequest.builder().messages(List.of(UserMessage.from("hi"))).build();
        assertThrows(IllegalStateException.class, () -> sanitizer.chat(req));
    }

    @Test
    void testSanitizationPassesThroughValidConversation() {
        ChatModel mockModel = mock(ChatModel.class);
        ChatResponse expectedResp =
                ChatResponse.builder().aiMessage(AiMessage.from("hello")).build();
        when(mockModel.chat(any(ChatRequest.class))).thenReturn(expectedResp);

        NvidiaMessageSanitizer sanitizer = new NvidiaMessageSanitizer(mockModel);
        ChatRequest req = ChatRequest.builder()
                .messages(List.of(
                        SystemMessage.from("You are an assistant"),
                        UserMessage.from("Hello"),
                        AiMessage.from("Hi there!"),
                        UserMessage.from("Next question")))
                .build();

        ChatResponse resp = sanitizer.chat(req);
        assertNotNull(resp);
        assertEquals("hello", resp.aiMessage().text());
    }
}
