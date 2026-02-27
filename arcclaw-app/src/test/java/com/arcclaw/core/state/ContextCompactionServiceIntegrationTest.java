package com.arcclaw.core.state;

import com.arcclaw.core.audit.AuditEvent;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.common.annotation.Identifier;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

@QuarkusTest
@TestProfile(CompactionTestProfile.class)
class ContextCompactionServiceIntegrationTest {

    private static final String TEST_SESSION_ID = "integration-session-123";

    @Inject
    ContextCompactionService service;

    @Inject
    PersistentChatMemoryStore chatMemoryStore;

    @Inject
    CompactionSummaryRepository summaryRepository;

    @Inject
    EntityManager em;

    @InjectMock
    @Identifier("compaction")
    ChatLanguageModel compactionModel;

    @Inject
    com.arcclaw.core.audit.AuditService auditService;

    @BeforeEach
    void setup() {
        WorkspaceScopeHolder.set(TEST_SESSION_ID);
        // Clear old DB state just in case
        chatMemoryStore.deleteMessages(TEST_SESSION_ID);
    }

    @AfterEach
    void teardown() {
        WorkspaceScopeHolder.clear();
    }

    @Test
    void testEndToEndCompaction() {
        // Arrange
        List<ChatMessage> seedMessages = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            seedMessages.add(UserMessage.from("User msg " + i));
        }
        chatMemoryStore.updateMessages(TEST_SESSION_ID, seedMessages);

        when(compactionModel.chat(any(dev.langchain4j.data.message.UserMessage.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("Prior session: user asked about X..."))
                        .build());

        // Act
        boolean result = service.compactIfNeeded(TEST_SESSION_ID);

        // Assert
        assertTrue(result, "Compaction should have taken place");
        
        List<ChatMessage> kept = chatMemoryStore.getMessages(TEST_SESSION_ID);
        assertEquals(2, kept.size(), "Should keep exactly keep-recent=2 messages");
        
        var summaryOpt = summaryRepository.findLatestBySessionId(TEST_SESSION_ID);
        assertTrue(summaryOpt.isPresent());
        assertEquals("Prior session: user asked about X...", summaryOpt.get().summary);
        assertEquals(2, summaryOpt.get().messagesCompacted);

        // Find the audit event
        auditService.waitForIdle();
        em.clear();
        long count = em.createQuery("SELECT count(e) FROM AuditEvent e WHERE e.sessionId = :sessionId AND e.eventType = :type", Long.class)
                .setParameter("sessionId", TEST_SESSION_ID)
                .setParameter("type", SecurityEventType.CONTEXT_COMPACTED)
                .getSingleResult();
        assertTrue(count > 0, "AuditService should fire CONTEXT_COMPACTED");
    }
}
