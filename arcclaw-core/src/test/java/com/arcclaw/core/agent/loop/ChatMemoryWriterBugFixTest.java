package com.arcclaw.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.arcclaw.core.security.InputSanitizer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ChatMemoryWriterBugFixTest {

    private ChatMemoryWriter writer;
    private ChatMemoryStore store;

    @BeforeEach
    void setUp() {
        writer = new ChatMemoryWriter();
        store = mock(ChatMemoryStore.class);
        writer.chatMemoryStore = store;
        writer.piiScanReflex = mock(InputSanitizer.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testScrubUnfulfilledToolCalls_removesOrphanedAiMessage() {
        String sessionId = "s1";
        ToolExecutionRequest req1 = ToolExecutionRequest.builder().id("c1").name("tool1").arguments("{}").build();
        ToolExecutionRequest req2 = ToolExecutionRequest.builder().id("c2").name("tool2").arguments("{}").build();

        List<ChatMessage> history = List.of(
                UserMessage.userMessage("first goal"),
                AiMessage.aiMessage("thought", List.of(req1)),
                new ToolExecutionResultMessage("c1", "tool1", "result1"),
                AiMessage.aiMessage("second thought", List.of(req2)) // This one is unfulfilled
        );

        when(store.getMessages(sessionId)).thenReturn(history);

        writer.scrubUnfulfilledToolCalls(sessionId);

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(store).updateMessages(eq(sessionId), captor.capture());

        List<ChatMessage> filtered = captor.getValue();
        assertEquals(3, filtered.size(), "Should have removed the last AiMessage with orphaned tool request");
        assertEquals("result1", ((ToolExecutionResultMessage) filtered.get(2)).text());
    }
}
