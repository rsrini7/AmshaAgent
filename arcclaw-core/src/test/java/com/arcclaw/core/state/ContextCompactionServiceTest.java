package com.arcclaw.core.state;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.arcclaw.core.audit.AuditService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ContextCompactionServiceTest {

    ContextCompactionService service;
    PersistentChatMemoryStore mockChatStore;
    CompactionSummaryRepository mockRepo;
    AuditService mockAudit;
    ChatModel mockModel;

    @BeforeEach
    void setup() {
        service = new ContextCompactionService();
        mockChatStore = mock(PersistentChatMemoryStore.class);
        mockRepo = mock(CompactionSummaryRepository.class);
        mockAudit = mock(AuditService.class);
        mockModel = mock(ChatModel.class);

        service.chatMemoryStore = mockChatStore;
        service.summaryRepository = mockRepo;
        service.auditService = mockAudit;
        service.compactionModel = mockModel;
        
        service.enabled = true;
        service.threshold = 10;
        service.keepRecent = 5;
    }

    @Test
    void testBypassWhenDisabled() {
        service.enabled = false;
        assertFalse(service.compact("session1", "test-user"));
        verifyNoInteractions(mockChatStore);
    }

    @Test
    void testBelowThresholdNoop() {
        // LangChain4j 0.26.x→1.x migration: renamed .from() to factory method
        List<ChatMessage> msgs = List.of(UserMessage.userMessage("1"), AiMessage.aiMessage("2"));
        when(mockChatStore.getMessages("session1")).thenReturn(msgs);
        assertFalse(service.compact("session1", "test-user"));
        verifyNoInteractions(mockModel);
    }

    @Test
    void testCompactionTriggersAtThreshold() {
        List<ChatMessage> msgs = new ArrayList<>();
        // LangChain4j 0.26.x→1.x migration: renamed .from() to factory method
        msgs.add(SystemMessage.systemMessage("SYS"));
        for (int i=0; i<10; i++) {
            msgs.add(UserMessage.userMessage("Msg" + i));
        }
        
        when(mockChatStore.getMessages("session1")).thenReturn(msgs);
        when(mockRepo.findLatestBySessionId("session1")).thenReturn(Optional.empty());
        when(mockModel.chat(any(UserMessage.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage("New Summary Text"))
                .build());

        assertTrue(service.compact("session1", "test-user"));

        verify(mockModel, times(1)).chat(any(UserMessage.class));
        verify(mockRepo, times(1)).upsertSummary("session1", "test-user", "New Summary Text", 5);
        verify(mockChatStore, times(1)).updateMessages(eq("session1"), anyList());
        verify(mockAudit, times(1)).record(eq("session1"), eq("system"), eq("system"), any(), anyString());
    }

    @Test
    void testBlankGuardHandling() {
        List<ChatMessage> msgs = new ArrayList<>();
        for (int i=0; i<10; i++) {
            // LangChain4j 0.26.x→1.x migration: renamed .from() to factory method
            msgs.add(UserMessage.userMessage("Msg" + i));
        }
        when(mockChatStore.getMessages("session1")).thenReturn(msgs);
        // LangChain4j 0.26.x→1.x migration: renamed .from() to factory method
        when(mockModel.chat(any(UserMessage.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage("   \n "))
                .build());

        assertFalse(service.compact("session1", "test-user"));
        verify(mockRepo, never()).upsertSummary(any(), any(), any(), anyInt());
    }
}
