package com.amsha.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.List;
import java.util.Optional;

import com.amsha.core.test.MockChatMemoryStorePort;
import com.amsha.memory.config.TieredMemoryConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class PersistentChatMemoryStoreTest {

    private MockChatMemoryStorePort memoryPort;
    private PersistentChatMemoryStore store;
    private TieredMemoryConfig tieredConfig;

    @BeforeEach
    void setUp() {
        memoryPort = new MockChatMemoryStorePort();
        tieredConfig = mock(TieredMemoryConfig.class);
        when(tieredConfig.enabled()).thenReturn(false);

        store = new PersistentChatMemoryStore();
        store.memoryStore = memoryPort;
        store.compactionEventBridge = mock(CompactionEventBridge.class);
        store.objectMapper = new ObjectMapper();
        store.tieredConfig = tieredConfig;
    }

    @Test
    void firstUserMessageSurvivesRepeatedCompactionWindows() {
        String sessionId = "first-message-retention";
        String original = "The original request that began the session";
        List<ChatMessage> initialMessages =
                List.of(UserMessage.userMessage(original), AiMessage.aiMessage("Initial response"));

        store.updateMessages(sessionId, initialMessages);
        assertEquals(Optional.of(original), store.getFirstUserMessage(sessionId));

        for (int iteration = 0; iteration < 3; iteration++) {
            store.updateMessages(
                    sessionId,
                    List.of(
                            UserMessage.userMessage("Recent user message " + iteration),
                            AiMessage.aiMessage("Recent response " + iteration)));
            assertEquals(Optional.of(original), store.getFirstUserMessage(sessionId));
        }

        store.rememberFirstUserMessageIfMissing(sessionId, "A later message");
        assertEquals(Optional.of(original), store.getFirstUserMessage(sessionId));
    }
}
