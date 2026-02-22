package com.arcclaw.core.state;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@QuarkusTest
public class PersistentChatMemoryStoreTest {

    @Inject
    ChatMemoryStore chatMemoryStore;

    @Inject
    EntityManager em;

    @Test
    public void testPersistence() {
        String sessionId = UUID.randomUUID().toString();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("Hello"));
        messages.add(AiMessage.from("Hi there!"));

        // 1. Update messages
        chatMemoryStore.updateMessages(sessionId, messages);

        // 2. Retrieve and verify
        List<ChatMessage> retrieved = chatMemoryStore.getMessages(sessionId);
        Assertions.assertEquals(2, retrieved.size());
        Assertions.assertTrue(retrieved.get(0) instanceof UserMessage);
        Assertions.assertEquals("Hello", ((UserMessage) retrieved.get(0)).text());
        Assertions.assertTrue(retrieved.get(1) instanceof AiMessage);
        Assertions.assertEquals("Hi there!", ((AiMessage) retrieved.get(1)).text());

        // 3. Verify in DB directly
        ChatMemoryEntity entity = em.find(ChatMemoryEntity.class, sessionId);
        Assertions.assertNotNull(entity);
        Assertions.assertTrue(entity.getMessagesJson().contains("Hello"));
        Assertions.assertTrue(entity.getMessagesJson().contains("Hi there!"));

        // 4. Delete and verify
        chatMemoryStore.deleteMessages(sessionId);
        Assertions.assertEquals(0, chatMemoryStore.getMessages(sessionId).size());

        em.clear(); // Clear L1 cache to force reload from DB
        Assertions.assertNull(em.find(ChatMemoryEntity.class, sessionId));
    }
}
