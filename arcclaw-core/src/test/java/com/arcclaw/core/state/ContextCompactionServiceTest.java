package com.arcclaw.core.state;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
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

    @Test
    void testTruncationPreservesSurrogatePairs() {
        // Construct a string of 2000 ASCII chars followed by a 4-byte emoji (surrogate pair)
        StringBuilder sb = new StringBuilder();
        sb.append("A".repeat(2000));
        sb.append("\uD83D\uDE00"); // 😀
        String longText = sb.toString();
        
        when(mockChatStore.getMessages("session1")).thenReturn(createMessages(10));
        when(mockRepo.findLatestBySessionId("session1")).thenReturn(Optional.empty());
        when(mockModel.chat(any(UserMessage.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage(longText))
                .build());

        assertTrue(service.compact("session1", "test-user"));

        // Capture the summary passed to upsertSummary
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mockRepo).upsertSummary(eq("session1"), eq("test-user"), captor.capture(), anyInt());
        
        String truncated = captor.getValue();
        assertTrue(truncated.endsWith("...[truncated]"));
        // Check that we didn't slice mid-surrogate-pair
        // The last char of the truncated message (before suffix) should be "A" (the 1985th char)
        char lastChar = truncated.charAt(1984);
        assertEquals('A', lastChar);
        assertFalse(Character.isSurrogate(lastChar));
        
        // Let's test a more specific case where the surrogate pair is AT the boundary
        sb = new StringBuilder();
        sb.append("A".repeat(1984));
        sb.append("\uD83D\uDE00"); // 😀 at 1985th codepoint
        sb.append("extra");
        longText = sb.toString();
        
        reset(mockRepo);
        when(mockModel.chat(any(UserMessage.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage(longText))
                .build());
        
        assertTrue(service.compact("session1", "test-user"));
        verify(mockRepo).upsertSummary(eq("session1"), eq("test-user"), captor.capture(), anyInt());
        truncated = captor.getValue();
        
        // 1984 'A's + 1 Emoji = 1985 codepoints. 
        // limit(1985) should take 1984 'A's and 1 Emoji.
        assertTrue(truncated.contains("\uD83D\uDE00...[truncated]"));
        int emojiStart = truncated.indexOf("\uD83D\uDE00");
        assertEquals(1984, emojiStart);
        // Verify no malformed surrogates
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
        when(mockRepo.findLatestBySessionId("session1")).thenReturn(Optional.empty());
        when(mockModel.chat(any(UserMessage.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage(shortText))
                .build());

        assertTrue(service.compact("session1", "test-user"));
        verify(mockRepo).upsertSummary(eq("session1"), eq("test-user"), eq(shortText), anyInt());
    }

    @Test
    void testTruncationSuffixPresent() {
        String longText = "A".repeat(1986);
        when(mockChatStore.getMessages("session1")).thenReturn(createMessages(10));
        when(mockRepo.findLatestBySessionId("session1")).thenReturn(Optional.empty());
        when(mockModel.chat(any(UserMessage.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage(longText))
                .build());

        assertTrue(service.compact("session1", "test-user"));
        verify(mockRepo).upsertSummary(eq("session1"), eq("test-user"), contains("...[truncated]"), anyInt());
    }

    @Test
    void truncationIsCodepointSafeAndReturnsTruncationSuffix() throws Exception {
        // 1984 'A's + 1 Emoji (2 chars, 1 codepoint) + 10 'B's
        String input = "A".repeat(1984) + "\uD83D\uDE00" + "B".repeat(10);
        
        java.lang.reflect.Method m = ContextCompactionService.class.getDeclaredMethod("truncateSafely", String.class);
        m.setAccessible(true);
        String result = (String) m.invoke(service, input);
        
        String expected = "A".repeat(1984) + "\uD83D\uDE00...[truncated]";
        assertEquals(expected, result);
    }
}
