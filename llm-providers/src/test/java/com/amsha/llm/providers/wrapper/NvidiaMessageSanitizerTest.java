package com.amsha.llm.providers.wrapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.ArrayList;
import java.util.List;

import com.amsha.llm.providers.catalog.*;
import com.amsha.llm.providers.helper.*;
import com.amsha.llm.providers.reasoning.*;
import com.amsha.llm.providers.routing.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("java:S1192")
class NvidiaMessageSanitizerTest {

    private NvidiaMessageSanitizer sanitizer;
    private ChatModel mockModel;

    @BeforeEach
    void setUp() {
        mockModel = mock(ChatModel.class);
        sanitizer = new NvidiaMessageSanitizer(mockModel);
    }

    @Test
    void testChatDelegatesToModel() {
        ChatResponse mockResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from("Sanitized answer"))
                .build();
        when(mockModel.chat(any(ChatRequest.class))).thenReturn(mockResponse);

        ChatRequest request = ChatRequest.builder()
                .messages(List.of(UserMessage.from("Hello")))
                .build();
        ChatResponse response = sanitizer.chat(request);

        assertNotNull(response);
        assertEquals("Sanitized answer", response.aiMessage().text());
    }

    @Test
    void testRewriteAllIdsForAbsoluteSafety() {
        List<ToolExecutionRequest> requests = List.of(
                ToolExecutionRequest.builder()
                        .id("call_123")
                        .name("tool1")
                        .arguments("{}")
                        .build(),
                ToolExecutionRequest.builder()
                        .id("too-long-id-123")
                        .name("tool2")
                        .arguments("{}")
                        .build());
        AiMessage aiMessage = AiMessage.from(requests);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(aiMessage);
        messages.add(ToolExecutionResultMessage.from("call_123", "tool1", "result1"));
        messages.add(ToolExecutionResultMessage.from("too-long-id-123", "tool2", "result2"));

        List<ChatMessage> sanitized = sanitizer.sanitize(messages);

        assertEquals(3, sanitized.size());
        AiMessage sanitizedAi = (AiMessage) sanitized.get(0);

        String id1 = sanitizedAi.toolExecutionRequests().get(0).id();
        String id2 = sanitizedAi.toolExecutionRequests().get(1).id();

        assertEquals(9, id1.length());
        assertEquals(9, id2.length());
        assertTrue(id1.matches("[a-zA-Z0-9]{9}"));
        assertTrue(id2.matches("[a-zA-Z0-9]{9}"));

        assertEquals(id1, ((ToolExecutionResultMessage) sanitized.get(1)).id());
        assertEquals(id2, ((ToolExecutionResultMessage) sanitized.get(2)).id());
    }

    @Test
    void testBipartiteGuardPreservesPairedMessages() {
        List<ToolExecutionRequest> requests = List.of(ToolExecutionRequest.builder()
                .id("id1234567")
                .name("tool1")
                .arguments("{}")
                .build());
        AiMessage aiMessage = AiMessage.from(requests);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(aiMessage);
        messages.add(ToolExecutionResultMessage.from("id1234567", "tool1", "result1"));

        List<ChatMessage> sanitized = sanitizer.sanitize(messages);
        assertEquals(2, sanitized.size());
    }

    @Test
    void testBipartiteGuardPrunesOrphanedCalls() {
        List<ToolExecutionRequest> requests = List.of(ToolExecutionRequest.builder()
                .id("orphan01x")
                .name("orphan-tool")
                .arguments("{}")
                .build());
        AiMessage orphanAi = AiMessage.from("Thinking...", requests);
        AiMessage followUp = AiMessage.from("Next message after orphan");

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(orphanAi);
        messages.add(followUp);

        List<ChatMessage> sanitized = sanitizer.sanitize(messages);

        assertEquals(3, sanitized.size());
        AiMessage sanitizedOrphan = (AiMessage) sanitized.get(0);
        assertFalse(sanitizedOrphan.hasToolExecutionRequests());
        assertEquals("Thinking...", sanitizedOrphan.text());
        assertTrue(sanitized.get(2) instanceof UserMessage);
    }

    @Test
    void testBipartiteGuardPrunesOrphanedResults() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("hello"));
        messages.add(ToolExecutionResultMessage.from("no-call-id", "tool1", "orphaned-result"));

        List<ChatMessage> sanitized = sanitizer.sanitize(messages);

        assertEquals(1, sanitized.size());
        assertEquals("hello", ((UserMessage) sanitized.get(0)).singleText());
    }

    @Test
    void testBracketPatternStripping() {
        String thought = "I will call tool1 [tool1(arg1=val1)] and tool2 [tool2(arg2=val2)]";

        List<ToolExecutionRequest> requests = List.of(ToolExecutionRequest.builder()
                .id("id1")
                .name("tool1")
                .arguments("{}")
                .build());
        AiMessage aiMessage = AiMessage.from(thought, requests);

        List<ChatMessage> messages = List.of(aiMessage, ToolExecutionResultMessage.from("id1", "tool1", "done"));

        List<ChatMessage> sanitized = sanitizer.sanitize(messages);
        AiMessage sanitizedAi = (AiMessage) sanitized.get(0);

        assertFalse(sanitizedAi.text().contains("[tool1("));
        assertFalse(sanitizedAi.text().contains("[tool2("));
        assertTrue(sanitizedAi.text().contains("I will call tool1"));
    }

    @Test
    void testMixedMessagesPassThrough() {
        List<ChatMessage> messages = List.of(UserMessage.from("hello"), AiMessage.from("thought"));
        List<ChatMessage> sanitized = sanitizer.sanitize(messages);
        assertEquals(3, sanitized.size());
        assertEquals("hello", ((UserMessage) sanitized.get(0)).singleText());
        assertTrue(sanitized.get(2) instanceof UserMessage);
    }
}
