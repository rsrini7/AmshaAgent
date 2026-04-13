package com.arcclaw.core.state;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.arcclaw.core.audit.AuditService;
import com.arcclaw.persistence.api.CompactionStorePort;
import com.arcclaw.persistence.api.CompactionSummaryDto;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CompactionServiceTest {

    CompactionService service;
    PersistentChatMemoryStore mockChatStore;
    CompactionStorePort mockPort;
    AuditService mockAudit;
    ChatModel mockModel;

    @BeforeEach
    void setup() {
        service = new CompactionService();
        mockChatStore = mock(PersistentChatMemoryStore.class);
        mockPort = mock(CompactionStorePort.class);
        mockAudit = mock(AuditService.class);
        mockModel = mock(ChatModel.class);

        service.chatMemoryStore = mockChatStore;
        service.summaryRepository = mockPort;
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
        List<ChatMessage> msgs = List.of(UserMessage.userMessage("1"), AiMessage.aiMessage("2"));
        when(mockChatStore.getMessages("session1")).thenReturn(msgs);
        assertFalse(service.compact("session1", "test-user"));
        verifyNoInteractions(mockModel);
    }

    @Test
    void testCompactionTriggersAtThreshold() {
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(SystemMessage.systemMessage("SYS"));
        for (int i=0; i<10; i++) {
            msgs.add(UserMessage.userMessage("Msg" + i));
        }
        
        when(mockChatStore.getMessages("session1")).thenReturn(msgs);
        when(mockPort.findLatestBySessionId("session1")).thenReturn(Optional.empty());
        when(mockModel.chat(any(UserMessage.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage("New Summary Text"))
                .build());

        assertTrue(service.compact("session1", "test-user"));

        verify(mockModel, times(1)).chat(any(UserMessage.class));
        verify(mockPort, times(1)).upsert(any(CompactionSummaryDto.class));
        verify(mockChatStore, times(1)).updateMessages(eq("session1"), anyList());
        verify(mockAudit, times(1)).record(eq("session1"), eq("system"), eq("system"), any(), anyString());
    }

    @Test
    void testBlankGuardHandling() {
        List<ChatMessage> msgs = new ArrayList<>();
        for (int i=0; i<10; i++) {
            msgs.add(UserMessage.userMessage("Msg" + i));
        }
        when(mockChatStore.getMessages("session1")).thenReturn(msgs);
        when(mockModel.chat(any(UserMessage.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage("   \n "))
                .build());

        assertFalse(service.compact("session1", "test-user"));
        verify(mockPort, never()).upsert(any());
    }

    @Test
    void testTruncationPreservesSurrogatePairs() {
        StringBuilder sb = new StringBuilder();
        sb.append("A".repeat(2000));
        sb.append("\uD83D\uDE00"); // 😀
        String longText = sb.toString();
        
        when(mockChatStore.getMessages("session1")).thenReturn(createMessages(10));
        when(mockPort.findLatestBySessionId("session1")).thenReturn(Optional.empty());
        when(mockModel.chat(any(UserMessage.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage(longText))
                .build());

        assertTrue(service.compact("session1", "test-user"));

        ArgumentCaptor<CompactionSummaryDto> captor = ArgumentCaptor.forClass(CompactionSummaryDto.class);
        verify(mockPort).upsert(captor.capture());
        
        String truncated = captor.getValue().summary();
        assertTrue(truncated.endsWith("...[truncated]"));
        char lastChar = truncated.charAt(1984);
        assertEquals('A', lastChar);
        assertFalse(Character.isSurrogate(lastChar));
        
        sb = new StringBuilder();
        sb.append("A".repeat(1984));
        sb.append("\uD83D\uDE00"); // 😀 at 1985th codepoint
        sb.append("extra");
        longText = sb.toString();
        
        reset(mockPort);
        when(mockModel.chat(any(UserMessage.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage(longText))
                .build());
        
        assertTrue(service.compact("session1", "test-user"));
        verify(mockPort).upsert(captor.capture());
        truncated = captor.getValue().summary();
        
        assertTrue(truncated.contains("\uD83D\uDE00...[truncated]"));
        int emojiStart = truncated.indexOf("\uD83D\uDE00");
        assertEquals(1984, emojiStart);
        assertTrue(Character.isHighSurrogate(truncated.charAt(1984)));
        assertTrue(Character.isLowSurrogate(truncated.charAt(1985)));
    }

    private List<ChatMessage> createMessages(int count) {
        List<ChatMessage> msgs = new ArrayList<>();
        for (int i=0; i<count; i++) {
            msgs.add(UserMessage.userMessage("Msg" + i));
        }
        return msgs;
    }

    @Test
    void testShortTextIsNotTruncated() {
        String shortText = "A".repeat(1985);
        when(mockChatStore.getMessages("session1")).thenReturn(createMessages(10));
        when(mockPort.findLatestBySessionId("session1")).thenReturn(Optional.empty());
        when(mockModel.chat(any(UserMessage.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage(shortText))
                .build());

        assertTrue(service.compact("session1", "test-user"));
        ArgumentCaptor<CompactionSummaryDto> captor = ArgumentCaptor.forClass(CompactionSummaryDto.class);
        verify(mockPort).upsert(captor.capture());
        assertEquals(shortText, captor.getValue().summary());
    }

    @Test
    void testTruncationSuffixPresent() {
        String longText = "A".repeat(1986);
        when(mockChatStore.getMessages("session1")).thenReturn(createMessages(10));
        when(mockPort.findLatestBySessionId("session1")).thenReturn(Optional.empty());
        when(mockModel.chat(any(UserMessage.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage(longText))
                .build());

        assertTrue(service.compact("session1", "test-user"));
        ArgumentCaptor<CompactionSummaryDto> captor = ArgumentCaptor.forClass(CompactionSummaryDto.class);
        verify(mockPort).upsert(captor.capture());
        assertTrue(captor.getValue().summary().contains("...[truncated]"));
    }

    @Test
    void truncationIsCodepointSafeAndReturnsTruncationSuffix() throws Exception {
        String input = "A".repeat(1984) + "\uD83D\uDE00" + "B".repeat(10);
        
        Method m = CompactionService.class.getDeclaredMethod("truncateSafely", String.class);
        m.setAccessible(true);
        String result = (String) m.invoke(service, input);
        
        String expected = "A".repeat(1984) + "\uD83D\uDE00...[truncated]";
        assertEquals(expected, result);
    }
}
