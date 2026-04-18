package com.arcclaw.core.routing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NvidiaMessageSanitizerTest {

    private NvidiaMessageSanitizer sanitizer;

    @BeforeEach
    void setUp() {
        sanitizer = new NvidiaMessageSanitizer(mock(ChatModel.class));
    }

    @Test
    void testRewriteNonCompliantIds() {
        List<ToolExecutionRequest> requests = List.of(
            ToolExecutionRequest.builder().id("call_123").name("tool1").arguments("{}").build(),
            ToolExecutionRequest.builder().id("too-long-id-123").name("tool2").arguments("{}").build()
        );
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
        
        assertEquals(id1, ((ToolExecutionResultMessage) sanitized.get(1)).id());
        assertEquals(id2, ((ToolExecutionResultMessage) sanitized.get(2)).id());
    }

    @Test
    void testNonOrphanedToolCallsPreserved() {
        List<ToolExecutionRequest> requests = List.of(
            ToolExecutionRequest.builder().id("id1234567").name("tool1").arguments("{}").build()
        );
        AiMessage aiMessage = AiMessage.from(requests);
        
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(aiMessage);
        messages.add(ToolExecutionResultMessage.from("id1234567", "tool1", "result1"));

        List<ChatMessage> sanitized = sanitizer.sanitize(messages);
        assertEquals(2, sanitized.size());
    }

    @Test
    void testPruneOrphanedToolCalls() {
        // AiMessage with a tool call request but no following
        // ToolExecutionResultMessage — this is the orphan scenario.
        // The sanitizer must drop this message to prevent history corruption.
        List<ToolExecutionRequest> requests = List.of(
            ToolExecutionRequest.builder()
                .id("orphan01x")
                .name("orphan-tool")
                .arguments("{}")
                .build()
        );
        AiMessage orphanAi = AiMessage.from(requests);
        AiMessage followUp = AiMessage.from("Next message after orphan");

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(orphanAi);
        messages.add(followUp);  // NOT a ToolExecutionResultMessage

        List<ChatMessage> sanitized = sanitizer.sanitize(messages);

        // The orphaned AiMessage must be dropped; only followUp remains.
        assertEquals(1, sanitized.size());
        assertInstanceOf(AiMessage.class, sanitized.get(0));
        assertEquals("Next message after orphan",
            ((AiMessage) sanitized.get(0)).text());
    }

    @Test
    void testBracketPatternVariations() {
        String thought = "I will call tool1 [tool1(arg1=val1)] and tool2 [tool2(arg2=val2)]";
        AiMessage aiMessage = AiMessage.from(thought);
        
        List<ChatMessage> messages = List.of(aiMessage);
        List<ChatMessage> sanitized = sanitizer.sanitize(messages);
        
        assertEquals(1, sanitized.size());
        AiMessage sanitizedAi = (AiMessage) sanitized.get(0);
        
        // Correct pattern per FIX #4 matches [...]
        assertFalse(sanitizedAi.text().contains("[tool1("));
        assertFalse(sanitizedAi.text().contains("[tool2("));
        assertTrue(sanitizedAi.text().contains("I will call tool1"));
    }

    @Test
    void testAlreadyCompliantIdsNotChanged() {
        String compliantId = "abc123XYZ"; 
        List<ToolExecutionRequest> requests = List.of(
            ToolExecutionRequest.builder().id(compliantId).name("tool1").arguments("{}").build()
        );
        List<ChatMessage> messages = List.of(
            AiMessage.from(requests),
            ToolExecutionResultMessage.from(compliantId, "tool1", "result1")
        );

        List<ChatMessage> sanitized = sanitizer.sanitize(messages);
        
        AiMessage sanitizedAi = (AiMessage) sanitized.get(0);
        assertEquals(compliantId, sanitizedAi.toolExecutionRequests().get(0).id());
        assertEquals(compliantId, ((ToolExecutionResultMessage) sanitized.get(1)).id());
    }

    @Test
    void testMixedMessagesPassThrough() {
        List<ChatMessage> messages = List.of(
            UserMessage.from("hello"),
            AiMessage.from("thought")
        );
        List<ChatMessage> sanitized = sanitizer.sanitize(messages);
        assertEquals(2, sanitized.size());
        assertEquals("hello", ((UserMessage) sanitized.get(0)).singleText());
    }
}
