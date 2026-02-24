package com.arcclaw.core.state;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * SQLite-backed ChatMemoryStore implementation for LangChain4j.
 * Overrides the default in-memory store using CDI @Alternative.
 */
@ApplicationScoped
@Alternative
@Priority(1)
public class PersistentChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = Logger.getLogger(PersistentChatMemoryStore.class);

    @Inject
    EntityManager em;

    @ConfigProperty(name = "quarkus.langchain4j.chat-memory.max-messages", defaultValue = "20")
    int rollingWindowSize;

    @Override
    @Transactional
    public List<ChatMessage> getMessages(Object memoryId) {
        log.debugf("ChatMemory getMessages called with memoryId: %s (type: %s)",
                memoryId, memoryId.getClass().getSimpleName());

        ChatMemoryEntity entity = em.find(ChatMemoryEntity.class, memoryId.toString());
        if (entity == null) {
            return new ArrayList<>();
        }
        
        // LangChain4j 0.26.1 utility for message serialization
        List<ChatMessage> allMessages = 
                ChatMessageDeserializer.messagesFromJson(entity.getMessagesJson());

        // Note: ArcClaw injects SystemMessages fresh in LangChainReasoningEngine.nextStep()
        // They are never stored in ChatMemoryStore, so systemMessages here is always empty.
        // This partition is preserved for future architectures that may store system messages.                
        // 1. Separate the sacred System Message(s) from the volatile history
        List<ChatMessage> systemMessages = allMessages.stream()
                .filter(m -> m instanceof SystemMessage)
                .toList();
                
        List<ChatMessage> nonSystemMessages = allMessages.stream()
                .filter(m -> !(m instanceof SystemMessage))
                .toList();
                
        // 2. If we are under the limit, return everything
        if (nonSystemMessages.size() <= rollingWindowSize) {
            return allMessages;
        }
        
        // 3. Apply the rolling window ONLY to non-system messages
        int fromIndex = nonSystemMessages.size() - rollingWindowSize;

        log.debugf("ChatMemory rolling window applied for session %s: pruned %d → %d messages (kept %d system)",
        memoryId, allMessages.size(), rollingWindowSize, systemMessages.size());
        
        List<ChatMessage> windowedMessages = new ArrayList<>(systemMessages); // Always keep identity
        windowedMessages.addAll(nonSystemMessages.subList(fromIndex, nonSystemMessages.size())); // Keep latest N
        
        return windowedMessages;
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
