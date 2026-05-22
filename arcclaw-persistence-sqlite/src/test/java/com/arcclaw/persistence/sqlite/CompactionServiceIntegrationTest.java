package com.arcclaw.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.state.CompactionService;
import com.arcclaw.core.state.PersistentChatMemoryStore;
import com.arcclaw.persistence.api.CompactionStorePort;
import com.arcclaw.persistence.api.CompactionSummaryDto;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.common.annotation.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(CompactionServiceIntegrationTest.CompactionTestProfile.class)
class CompactionServiceIntegrationTest {

    private static final String TEST_SESSION_ID = "integration-session-123";

    public static class CompactionTestProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "arcclaw.memory.compaction.enabled", "true",
                    "arcclaw.memory.compaction.threshold", "4",
                    "arcclaw.memory.compaction.keep-recent", "2",
                    "quarkus.langchain4j.groq.api-key", "mock-key-for-tests-only",
                    // Raise pool size above 1 to avoid deadlock between @Transactional
                    // compaction and the async persistence drain thread competing for the
                    // same connection (see application.yaml quarkus.datasource.max-size=1).
                    "quarkus.datasource.max-size", "2"
            );
        }
    }

    @Inject
    CompactionService service;

    @Inject
    PersistentChatMemoryStore chatMemoryStore;

    @Inject
    CompactionStorePort summaryStore;

    @Inject
    EntityManager em;

    @InjectMock
    @Identifier("compaction")
    ChatModel compactionModel;

    @Inject
    AuditService auditService;

    @BeforeEach
    void setup() {
        // No-op
    }

    @AfterEach
    void teardown() {
        // No-op
    }

    @Test
    void testEndToEndCompaction() {
        WorkspaceScopeHolder.run(TEST_SESSION_ID, () -> {
            // Arrange
            // Clear old DB state just in case
            chatMemoryStore.deleteMessages(TEST_SESSION_ID);

            List<ChatMessage> seedMessages = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                seedMessages.add(UserMessage.userMessage("User msg " + i));
            }
            chatMemoryStore.updateMessages(TEST_SESSION_ID, seedMessages);

            when(compactionModel.chat(any(UserMessage.class)))
                    .thenReturn(ChatResponse.builder()
                            .aiMessage(AiMessage.aiMessage("Prior session: user asked about X..."))
                            .build());

            // Act
            boolean result = service.compact(TEST_SESSION_ID, "test-user");

            // Assert
            assertTrue(result, "Compaction should have taken place");
            
            List<ChatMessage> kept = chatMemoryStore.getMessages(TEST_SESSION_ID);
            assertEquals(2, kept.size(), "Should keep exactly keep-recent=2 messages");
            
            Optional<CompactionSummaryDto> summaryOpt = summaryStore.findLatestBySessionId(TEST_SESSION_ID);
            assertTrue(summaryOpt.isPresent());
            assertEquals("Prior session: user asked about X...", summaryOpt.get().summary());
            assertEquals(2, summaryOpt.get().messagesCompacted());

            // Find the audit event
            auditService.waitForIdle();
            em.clear();
            long count = em.createQuery("SELECT count(e) FROM AuditEvent e WHERE e.sessionId = :sessionId AND e.eventType = :type", Long.class)
                    .setParameter("sessionId", TEST_SESSION_ID)
                    .setParameter("type", SecurityEventType.CONTEXT_COMPACTED)
                    .getSingleResult();
            assertTrue(count > 0, "AuditService should fire CONTEXT_COMPACTED");
        });
    }
}
