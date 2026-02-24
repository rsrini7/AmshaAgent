package com.arcclaw.core.state;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
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

    @Test
    void testRollingWindowPreservesSystemMessages() {
        String sessionId = UUID.randomUUID().toString();
        List<ChatMessage> messages = new ArrayList<>();
        
        // 1. Add 1 System Message
        messages.add(SystemMessage.from("Identity"));
        
        // 2. Add 24 User Messages (Total 25)
        for (int i = 1; i <= 24; i++) {
            messages.add(UserMessage.from("Message " + i));
        }
        
        chatMemoryStore.updateMessages(sessionId, messages);
        
        // 3. Retrieve
        List<ChatMessage> retrieved = chatMemoryStore.getMessages(sessionId);
        
        // Window size is 20 (non-system) + 1 (system) = 21
        Assertions.assertEquals(21, retrieved.size());
        
        // First message must be the System message
        Assertions.assertTrue(retrieved.get(0) instanceof SystemMessage);
        
        // Last 20 messages should be "Message 5" through "Message 24"
        Assertions.assertEquals("Message 5", ((UserMessage) retrieved.get(1)).text());
        Assertions.assertEquals("Message 24", ((UserMessage) retrieved.get(20)).text());
    }

    @Test
    void testUnderLimitReturnsAll() {
        String sessionId = UUID.randomUUID().toString();
        List<ChatMessage> messages = new ArrayList<>();
        
        for (int i = 1; i <= 15; i++) {
            messages.add(UserMessage.from("Under Limit " + i));
        }
        
        chatMemoryStore.updateMessages(sessionId, messages);
        
        List<ChatMessage> retrieved = chatMemoryStore.getMessages(sessionId);
        Assertions.assertEquals(15, retrieved.size());
    }

    @Test
    void testMultipleSystemMessagesAlwaysPreserved() {
        String sessionId = UUID.randomUUID().toString();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("Persona prompt"));
        messages.add(SystemMessage.from("Tool binding contract"));
        for (int i = 1; i <= 22; i++) {
            messages.add(UserMessage.from("msg " + i));
        }
        chatMemoryStore.updateMessages(sessionId, messages);
        List<ChatMessage> retrieved = chatMemoryStore.getMessages(sessionId);

        // 2 system + 20 non-system = 22
        Assertions.assertEquals(22, retrieved.size());
        long systemCount = retrieved.stream()
                .filter(m -> m instanceof SystemMessage).count();
        Assertions.assertEquals(2, systemCount, 
                "Both SystemMessages must survive rolling window");
    }
}
