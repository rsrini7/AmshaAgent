package com.arcclaw.core.reflex;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.state.CompactionSummaryEntity;
import com.arcclaw.core.state.CompactionSummaryRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class HistoryReflexTest {

    @Inject
    HistoryReflex historyReflex;

    @InjectMock
    ChatMemoryStore chatMemoryStore;

    @InjectMock
    CompactionSummaryRepository compactionSummaryRepository;

    private MessageContext createContext(String input) {
        ChannelUser user = new ChannelUser("user1", "user1", "user1", ChannelType.TELEGRAM);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, input, "test-session", Instant.now());
        return new MessageContext("chat1", user, msg, mock(ChannelSender.class));
    }

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
        summary.setSummary("Prior history summarized.");
        summary.setMessagesCompacted(10);
        
        when(compactionSummaryRepository.findLatestBySessionId(sessionId))
            .thenReturn(Optional.of(summary));
            
        // LangChain4j 0.26.x→1.x migration: renamed .from() to factory method
        List<ChatMessage> messages = List.of(
            UserMessage.userMessage("User prompt"),
            AiMessage.aiMessage("AI response")
        );
        when(chatMemoryStore.getMessages(sessionId)).thenReturn(messages);
        
        ReflexResult reflexResult = historyReflex.execute(sessionId, "user1", "", "/history", createContext("/history"));
        String result = reflexResult.commandResponse().orElse("");
        
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
        
        ReflexResult reflexResult = historyReflex.execute("s1", "u1","", "/history", createContext("/history"));
        String result = reflexResult.commandResponse().orElse("");
        Assertions.assertEquals("📭 No conversation history found for this session.", result);
    }
}
