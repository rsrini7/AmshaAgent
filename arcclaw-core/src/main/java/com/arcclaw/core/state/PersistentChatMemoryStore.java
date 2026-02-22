package com.arcclaw.core.state;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * SQLite-backed ChatMemoryStore implementation for LangChain4j.
 * Overrides the default in-memory store using CDI @Alternative.
 */
@ApplicationScoped
@Alternative
@Priority(1)
public class PersistentChatMemoryStore implements ChatMemoryStore {

    @Inject
    EntityManager em;

    @Override
    @Transactional
    public List<ChatMessage> getMessages(Object memoryId) {
        Log.debugf("ChatMemory getMessages called with memoryId: %s (type: %s)",
                memoryId, memoryId.getClass().getSimpleName());

        ChatMemoryEntity entity = em.find(ChatMemoryEntity.class, memoryId.toString());
        if (entity == null) {
            return new ArrayList<>();
        }

        // LangChain4j 0.26.1 utility for message serialization
        return ChatMessageDeserializer.messagesFromJson(entity.getMessagesJson());
    }

    @Override
    @Transactional
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String json = ChatMessageSerializer.messagesToJson(messages);

        // Use merge to either insert or update the record
        em.merge(new ChatMemoryEntity(memoryId.toString(), json));
    }

    @Override
    @Transactional
    public void deleteMessages(Object memoryId) {
        ChatMemoryEntity entity = em.find(ChatMemoryEntity.class, memoryId.toString());
        if (entity != null) {
            em.remove(entity);
        }
    }
}
