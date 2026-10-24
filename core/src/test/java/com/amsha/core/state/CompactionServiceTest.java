package com.amsha.core.state;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.amsha.core.audit.AuditService;
import com.amsha.core.events.CompactionEvent;
import com.amsha.core.governance.RateLimitTracker;
import com.amsha.core.governance.SessionBudgetTracker;
import com.amsha.llm.api.StrictJsonSchemaCapable;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.llm.api.StructuredSchemaRequests;
import com.amsha.llm.api.error.LlmMalformedResponseException;
import com.amsha.memory.config.TieredMemoryConfig;
import com.amsha.memory.tiered.RecallMemoryService;
import com.amsha.persistence.api.CompactionStorePort;
import com.amsha.persistence.api.CompactionSummaryDto;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class CompactionServiceTest {

    CompactionService service;
    PersistentChatMemoryStore mockChatStore;
    CompactionStorePort mockPort;
    AuditService mockAudit;
    ChatModel mockModel;
    TieredMemoryConfig mockTieredConfig;
    RecallMemoryService mockRecallService;

    Event<CompactionEvent> mockCompactionEvent;

    @BeforeEach
    void setup() {
        service = new CompactionService();
        mockChatStore = mock(PersistentChatMemoryStore.class);
        mockPort = mock(CompactionStorePort.class);
        mockAudit = mock(AuditService.class);
        mockModel = mock(ChatModel.class);
        mockTieredConfig = mock(TieredMemoryConfig.class);
        mockRecallService = mock(RecallMemoryService.class);
        mockCompactionEvent = mock(Event.class);

        when(mockTieredConfig.enabled()).thenReturn(false);

        SessionBudgetTracker budgetTracker = new SessionBudgetTracker();
        setField(budgetTracker, "meterRegistry", new SimpleMeterRegistry());
        service.budgetTracker = budgetTracker;

        service.chatMemoryStore = mockChatStore;
        service.summaryRepository = mockPort;
        service.auditService = mockAudit;
        service.compactionModel = mockModel;
        service.objectMapper = new ObjectMapper();
        service.tieredConfig = mockTieredConfig;
        service.recallService = mockRecallService;
        service.compactionEvent = mockCompactionEvent;

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
        for (int i = 0; i < 10; i++) {
            msgs.add(UserMessage.userMessage("Msg" + i));
        }

        when(mockChatStore.getMessages("session1")).thenReturn(msgs);
        when(mockPort.findLatestBySessionId("session1")).thenReturn(Optional.empty());
        when(mockModel.chat(any(UserMessage.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.aiMessage("{\"summary\": \"New Summary Text\"}"))
                        .build());

        assertTrue(service.compact("session1", "test-user"));

        verify(mockModel, times(1)).chat(any(UserMessage.class));
        verify(mockPort, times(1)).upsert(any(CompactionSummaryDto.class));
        verify(mockChatStore, times(1)).updateMessages(eq("session1"), anyList());
        verify(mockAudit, times(1)).record(eq("session1"), eq("system"), eq("system"), any(), anyString());
    }

    @Test
    void compactionPromptIncludesFirstUserMessage() {
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(UserMessage.userMessage("Hello, my name is Alice"));
        for (int i = 1; i < 10; i++) {
            msgs.add(UserMessage.userMessage("Msg" + i));
        }
        when(mockChatStore.getMessages("session1")).thenReturn(msgs);
        when(mockPort.findLatestBySessionId("session1")).thenReturn(Optional.empty());
        when(mockModel.chat(any(UserMessage.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.aiMessage("{\"summary\": \"Merged summary\"}"))
                        .build());

        assertTrue(service.compact("session1", "test-user"));

        ArgumentCaptor<UserMessage> promptCaptor = ArgumentCaptor.forClass(UserMessage.class);
        verify(mockModel).chat(promptCaptor.capture());
        String prompt = promptCaptor.getValue().singleText();
        assertTrue(prompt.contains("FIRST USER MESSAGE"));
        assertTrue(prompt.contains("Hello, my name is Alice"));
        assertTrue(prompt.contains("Preserve the first user message"));
    }

    @Test
    void compactionUsesPersistedFirstUserMessageAfterTheActiveWindowMovesOn() {
        List<ChatMessage> currentWindow = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            currentWindow.add(UserMessage.userMessage("Recent message " + i));
        }
        when(mockChatStore.getMessages("session1")).thenReturn(currentWindow);
        when(mockChatStore.getFirstUserMessage("session1"))
                .thenReturn(Optional.of("Original request from the start of the session"));
        when(mockPort.findLatestBySessionId("session1")).thenReturn(Optional.empty());
        when(mockModel.chat(any(UserMessage.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.aiMessage("{\"summary\": \"Merged summary\"}"))
                        .build());

        assertTrue(service.compact("session1", "test-user"));

        ArgumentCaptor<UserMessage> promptCaptor = ArgumentCaptor.forClass(UserMessage.class);
        verify(mockModel).chat(promptCaptor.capture());
        assertTrue(promptCaptor.getValue().singleText().contains("Original request from the start of the session"));
        verify(mockChatStore)
                .rememberFirstUserMessageIfMissing("session1", "Original request from the start of the session");
    }

    @Test
    void testBlankGuardHandling() {
        List<ChatMessage> msgs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            msgs.add(UserMessage.userMessage("Msg" + i));
        }
        when(mockChatStore.getMessages("session1")).thenReturn(msgs);
        when(mockModel.chat(any(UserMessage.class)))
                .thenReturn(ChatResponse.builder()
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
        when(mockModel.chat(any(UserMessage.class)))
                .thenReturn(ChatResponse.builder()
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
        when(mockModel.chat(any(UserMessage.class)))
                .thenReturn(ChatResponse.builder()
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
        for (int i = 0; i < count; i++) {
            msgs.add(UserMessage.userMessage("Msg" + i));
        }
        return msgs;
    }

    @Test
    void testShortTextIsNotTruncated() {
        String shortText = "A".repeat(1985);
        when(mockChatStore.getMessages("session1")).thenReturn(createMessages(10));
        when(mockPort.findLatestBySessionId("session1")).thenReturn(Optional.empty());
        when(mockModel.chat(any(UserMessage.class)))
                .thenReturn(ChatResponse.builder()
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
        when(mockModel.chat(any(UserMessage.class)))
                .thenReturn(ChatResponse.builder()
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

    @Test
    void testCompactRetriesOnRateLimit() {
        when(mockChatStore.getMessages("session1")).thenReturn(createMessages(10));
        when(mockPort.findLatestBySessionId("session1")).thenReturn(Optional.empty());

        // Fail twice with 429, then succeed
        when(mockModel.chat(any(UserMessage.class)))
                .thenThrow(new RuntimeException("Rate limit exceeded 429"))
                .thenThrow(new RuntimeException("Rate limit exceeded 429"))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.aiMessage("Success After Retries"))
                        .build());

        service.rateLimitTracker = mock(RateLimitTracker.class);

        assertTrue(service.compact("session1", "test-user"));

        verify(mockModel, times(3)).chat(any(UserMessage.class));
        verify(service.rateLimitTracker, times(2)).recordHit("compaction");
        verify(mockPort).upsert(any());
    }

    @Test
    void promptTemplatePreservesStableNonSecretIdentifiers() throws Exception {
        Field field = CompactionService.class.getDeclaredField("PROMPT_TEMPLATE");
        field.setAccessible(true);
        String template = (String) field.get(null);

        assertTrue(template.contains("preferred name"), "Compaction prompt should preserve preferred names");
        assertTrue(
                template.contains("GitHub username or repo owner"),
                "Compaction prompt should preserve GitHub usernames/owners");
        assertTrue(
                template.contains("Keep those identifiers verbatim"),
                "Compaction prompt should keep stable identifiers exact");
        assertTrue(
                template.contains("Do NOT preserve or restate secrets"),
                "Compaction prompt should exclude secrets from summaries");
        assertTrue(
                template.contains("Preserve the first user message"),
                "Compaction prompt should always preserve how the conversation started");
        assertFalse(
                template.contains("Discard: pleasantries"),
                "Compaction prompt must not discard the first message as a pleasantry");
    }

    private interface CapableChatModel extends ChatModel, StrictJsonSchemaCapable {}

    @Test
    void nativeSchema_attachedWhenStrictCapableAndNativeMode() {
        CapableChatModel capableModel = mock(CapableChatModel.class);
        when(capableModel.supportsStrictJsonSchema()).thenReturn(true);
        when(capableModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.aiMessage("{\"summary\": \"Strict schema summary\"}"))
                        .build());

        Instance<StructuredOutputMode> modeInst = mock(Instance.class);
        when(modeInst.isUnsatisfied()).thenReturn(false);
        when(modeInst.get()).thenReturn(StructuredOutputMode.NATIVE);

        service.compactionModel = capableModel;
        service.compactionModeInstance = modeInst;

        when(mockChatStore.getMessages("session1")).thenReturn(createMessages(10));
        when(mockPort.findLatestBySessionId("session1")).thenReturn(Optional.empty());

        assertTrue(service.compact("session1", "test-user"));

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(capableModel).chat(captor.capture());
        assertTrue(StructuredSchemaRequests.hasNativeJsonSchema(captor.getValue()));
    }

    @Test
    void nativeSchema_notAttachedWhenModeIsPromptDirective() {
        CapableChatModel capableModel = mock(CapableChatModel.class);
        when(capableModel.supportsStrictJsonSchema()).thenReturn(true);
        when(capableModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.aiMessage("Prompt directive summary"))
                        .build());

        Instance<StructuredOutputMode> modeInst = mock(Instance.class);
        when(modeInst.isUnsatisfied()).thenReturn(false);
        when(modeInst.get()).thenReturn(StructuredOutputMode.PROMPT_DIRECTIVE);

        service.compactionModel = capableModel;
        service.compactionModeInstance = modeInst;

        when(mockChatStore.getMessages("session1")).thenReturn(createMessages(10));
        when(mockPort.findLatestBySessionId("session1")).thenReturn(Optional.empty());

        assertTrue(service.compact("session1", "test-user"));

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(capableModel).chat(captor.capture());
        assertFalse(StructuredSchemaRequests.hasNativeJsonSchema(captor.getValue()));
    }

    @Test
    void nativeSchema_malformedResponse_retriesWithoutNativeSchema() {
        CapableChatModel capableModel = mock(CapableChatModel.class);
        when(capableModel.supportsStrictJsonSchema()).thenReturn(true);
        when(capableModel.chat(any(ChatRequest.class)))
                .thenThrow(new LlmMalformedResponseException("schema rejected"))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.aiMessage("Fallback prose summary after schema rejection"))
                        .build());
        when(capableModel.chat(any(UserMessage.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.aiMessage("Fallback prose summary after schema rejection"))
                        .build());

        Instance<StructuredOutputMode> modeInst = mock(Instance.class);
        when(modeInst.isUnsatisfied()).thenReturn(false);
        when(modeInst.get()).thenReturn(StructuredOutputMode.NATIVE);

        service.compactionModel = capableModel;
        service.compactionModeInstance = modeInst;

        when(mockChatStore.getMessages("session1")).thenReturn(createMessages(10));
        when(mockPort.findLatestBySessionId("session1")).thenReturn(Optional.empty());

        assertTrue(service.compact("session1", "test-user"));

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(capableModel, times(2)).chat(captor.capture());
        List<ChatRequest> requests = captor.getAllValues();
        assertTrue(StructuredSchemaRequests.hasNativeJsonSchema(requests.get(0)));
        assertFalse(StructuredSchemaRequests.hasNativeJsonSchema(requests.get(1)));
        UserMessage retryMsg = (UserMessage) requests.get(1).messages().get(0);
        assertTrue(retryMsg.singleText().contains("CRITICAL: You MUST respond ONLY with the summary prose"));
    }

    @Test
    void nativeSchema_extractsSummaryFromJsonContentAndResultFields() {
        when(mockChatStore.getMessages("session1")).thenReturn(createMessages(10));
        when(mockPort.findLatestBySessionId("session1")).thenReturn(Optional.empty());

        when(mockModel.chat(any(UserMessage.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.aiMessage("{\"content\": \"Content field summary\"}"))
                        .build());

        assertTrue(service.compact("session1", "test-user"));
        ArgumentCaptor<CompactionSummaryDto> captor = ArgumentCaptor.forClass(CompactionSummaryDto.class);
        verify(mockPort).upsert(captor.capture());
        assertEquals("Content field summary", captor.getValue().summary());

        reset(mockPort);
        when(mockModel.chat(any(UserMessage.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.aiMessage("{\"result\": \"Result field summary\"}"))
                        .build());

        assertTrue(service.compact("session1", "test-user"));
        verify(mockPort).upsert(captor.capture());
        assertEquals("Result field summary", captor.getValue().summary());
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
