package com.arcclaw.core.reflex;

import com.arcclaw.core.state.CompactionSummaryEntity;
import com.arcclaw.core.state.CompactionSummaryRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@QuarkusTest
public class HistoryReflexTest {

    @Inject
    HistoryReflex historyReflex;

    @InjectMock
    ChatMemoryStore chatMemoryStore;

    @InjectMock
    CompactionSummaryRepository compactionSummaryRepository;

    @Test
    void testMatches() {
        Assertions.assertTrue(historyReflex.matches("/history"));
        Assertions.assertTrue(historyReflex.matches("  /HISTORY  "));
        Assertions.assertFalse(historyReflex.matches("/status"));
    }

    @Test
    void testExecute_WithSummaryAndMessages() {
        String sessionId = "test-session";
        
        CompactionSummaryEntity summary = new CompactionSummaryEntity();
        summary.summary = "Prior history summarized.";
        summary.messagesCompacted = 10;
        
        when(compactionSummaryRepository.findLatestBySessionId(sessionId))
            .thenReturn(Optional.of(summary));
            
        List<ChatMessage> messages = List.of(
            UserMessage.from("User prompt"),
            AiMessage.from("AI response")
        );
        when(chatMemoryStore.getMessages(sessionId)).thenReturn(messages);
        
        String result = historyReflex.execute(sessionId, "user1", "/history");
        
        Assertions.assertTrue(result.contains("Prior history summarized."));
        Assertions.assertTrue(result.contains("User prompt"));
        Assertions.assertTrue(result.contains("AI response"));
        Assertions.assertTrue(result.contains("10 messages"));
    }

    @Test
    void testExecute_NoHistory() {
        when(compactionSummaryRepository.findLatestBySessionId(anyString()))
            .thenReturn(Optional.empty());
        when(chatMemoryStore.getMessages(anyString())).thenReturn(List.of());
        
        String result = historyReflex.execute("s1", "u1", "/history");
        Assertions.assertEquals("📭 No conversation history found for this session.", result);
    }
}
