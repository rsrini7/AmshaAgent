package com.arcclaw.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.List;
import java.util.Optional;

import com.arcclaw.app.ui.dto.ChatMessageDto;
import com.arcclaw.core.domain.model.AgentCheckpoint;
import com.arcclaw.core.state.PersistentChatMemoryStore;
import com.arcclaw.core.state.StatefulAgentStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatStreamResourceTest {

    @Mock
    PersistentChatMemoryStore chatMemoryStore;

    @Mock
    StatefulAgentStore statefulAgentStore;

    @Mock
    AgentCheckpoint checkpoint;

    ChatStreamResource resource;

    private static final String SESSION_ID = "sess-123";

    @BeforeEach
    void setUp() {
        resource = new ChatStreamResource();
        setField(resource, "chatMemoryStore", chatMemoryStore);
        setField(resource, "statefulAgentStore", statefulAgentStore);
    }

    @Test
    void historyReturnsMessagesForSession() {
        when(statefulAgentStore.findLatestAwaitingApproval(SESSION_ID)).thenReturn(Optional.empty());
        when(chatMemoryStore.getMessages(SESSION_ID))
                .thenReturn(List.of(new UserMessage("hello"), new AiMessage("hi there")));
        when(chatMemoryStore.getTimestamps(SESSION_ID)).thenReturn(List.of(1000L, 2000L));

        List<ChatMessageDto> result = resource.history(SESSION_ID);

        assertEquals(2, result.size());
        assertEquals("user", result.get(0).role());
        assertEquals("hello", result.get(0).text());
        assertEquals("assistant", result.get(1).role());
        assertEquals("hi there", result.get(1).text());
    }

    @Test
    void historyReturnsEmptyListWhenNoMessages() {
        when(statefulAgentStore.findLatestAwaitingApproval(SESSION_ID)).thenReturn(Optional.empty());
        when(chatMemoryStore.getMessages(SESSION_ID)).thenReturn(List.of());
        when(chatMemoryStore.getTimestamps(SESSION_ID)).thenReturn(List.of());

        List<ChatMessageDto> result = resource.history(SESSION_ID);

        assertTrue(result.isEmpty());
    }

    @Test
    void historyAttachesActionIdToLastApprovalMessage() {
        when(statefulAgentStore.findLatestAwaitingApproval(SESSION_ID)).thenReturn(Optional.of(checkpoint));
        when(checkpoint.getId()).thenReturn(42L);
        when(chatMemoryStore.getMessages(SESSION_ID))
                .thenReturn(List.of(
                        new UserMessage("run code"),
                        new AiMessage("I need to run this. \u23f8 Approval needed for sandbox_exec")));
        when(chatMemoryStore.getTimestamps(SESSION_ID)).thenReturn(List.of(1000L, 2000L));

        List<ChatMessageDto> result = resource.history(SESSION_ID);

        assertEquals(2, result.size());
        // The last assistant message with approval text should have the actionId
        ChatMessageDto lastAssistant = result.get(1);
        assertEquals(42L, lastAssistant.actionId());
    }

    @Test
    void historyHandlesTimestampMismatchGracefully() {
        when(statefulAgentStore.findLatestAwaitingApproval(SESSION_ID)).thenReturn(Optional.empty());
        when(chatMemoryStore.getMessages(SESSION_ID))
                .thenReturn(List.of(new UserMessage("hello"), new AiMessage("hi")));
        // Fewer timestamps than messages
        when(chatMemoryStore.getTimestamps(SESSION_ID)).thenReturn(List.of(1000L));

        List<ChatMessageDto> result = resource.history(SESSION_ID);

        assertEquals(2, result.size());
        assertEquals(1000L, result.get(0).timestamp());
        assertNull(result.get(1).timestamp());
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field: " + fieldName, e);
        }
    }
}
