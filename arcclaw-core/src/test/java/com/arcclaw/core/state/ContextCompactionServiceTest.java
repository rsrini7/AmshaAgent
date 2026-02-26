package com.arcclaw.core.state;

import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ContextCompactionServiceTest {

    ContextCompactionService service;
    PersistentChatMemoryStore mockChatStore;
    CompactionSummaryRepository mockRepo;
    AuditService mockAudit;
    ChatLanguageModel mockModel;

    @BeforeEach
    void setup() {
        service = new ContextCompactionService();
        mockChatStore = mock(PersistentChatMemoryStore.class);
        mockRepo = mock(CompactionSummaryRepository.class);
        mockAudit = mock(AuditService.class);
        mockModel = mock(ChatLanguageModel.class);

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
        assertFalse(service.compactIfNeeded("session1"));
        verifyNoInteractions(mockChatStore);
    }

    @Test
    void testBelowThresholdNoop() {
        List<ChatMessage> msgs = List.of(UserMessage.from("1"), AiMessage.from("2"));
        when(mockChatStore.getMessages("session1")).thenReturn(msgs);
        assertFalse(service.compactIfNeeded("session1"));
        verifyNoInteractions(mockModel);
    }

    @Test
    void testCompactionTriggersAtThreshold() {
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(SystemMessage.from("SYS"));
        for (int i=0; i<10; i++) {
            msgs.add(UserMessage.from("Msg" + i));
        }
        
        when(mockChatStore.getMessages("session1")).thenReturn(msgs);
        when(mockRepo.findLatestBySessionId("session1")).thenReturn(Optional.empty());
        when(mockModel.chat(any(UserMessage.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("New Summary Text"))
                .build());

        assertTrue(service.compactIfNeeded("session1"));

        verify(mockModel, times(1)).chat(any(UserMessage.class));
        verify(mockRepo, times(1)).upsertSummary("session1", "New Summary Text", 5);
        verify(mockChatStore, times(1)).updateMessages(eq("session1"), anyList());
        verify(mockAudit, times(1)).record(eq("session1"), eq("system"), eq("system"), any(), anyString());
    }

    @Test
    void testBlankGuardHandling() {
        List<ChatMessage> msgs = new ArrayList<>();
        for (int i=0; i<10; i++) {
            msgs.add(UserMessage.from("Msg" + i));
        }
        when(mockChatStore.getMessages("session1")).thenReturn(msgs);
        when(mockModel.chat(any(UserMessage.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("   \n "))
                .build());

        assertFalse(service.compactIfNeeded("session1"));
        verify(mockRepo, never()).upsertSummary(any(), any(), anyInt());
    }
}
