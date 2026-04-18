package com.arcclaw.core.state;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import com.arcclaw.persistence.api.ChatMemoryDto;
import com.arcclaw.persistence.api.ChatMemoryStorePort;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.quarkus.arc.Unremovable;

/**
 * SQLite-backed implementation of LangChain4j {@link ChatMemoryStore}.
 * Refactored to delegate to {@link ChatMemoryStorePort}.
 * §1.2: Zero EntityManager imports, zero JPQL.
 * 
 * Logic (§8): Implements a rolling window that preserves all SystemMessages
 * and keeps the last 20 non-system messages (User/AI/Tool).
 */
@ApplicationScoped
@Unremovable
public class PersistentChatMemoryStore implements ChatMemoryStore {

    private static final int MAX_NON_SYSTEM_MESSAGES = 20;

    @Inject
    ChatMemoryStorePort memoryStore;

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<ChatMessage> getMessages(Object memoryId) {
        String id = String.valueOf(memoryId);
        return memoryStore.findById(id)
                .map(dto -> ChatMessageDeserializer.messagesFromJson(dto.messagesJson()))
                .orElse(Collections.emptyList());
    }

    @Override
    @Transactional
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String id = String.valueOf(memoryId);
        
        List<ChatMessage> systemMessages = messages.stream()
                .filter(m -> m instanceof SystemMessage)
                .collect(Collectors.toList());
                
        List<ChatMessage> nonSystemMessages = messages.stream()
                .filter(m -> !(m instanceof SystemMessage))
                .collect(Collectors.toList());
                
        List<ChatMessage> toKeep = new ArrayList<>(systemMessages);
        if (nonSystemMessages.size() > MAX_NON_SYSTEM_MESSAGES) {
            toKeep.addAll(nonSystemMessages.subList(nonSystemMessages.size() - MAX_NON_SYSTEM_MESSAGES, nonSystemMessages.size()));
        } else {
            toKeep.addAll(nonSystemMessages);
        }
        
        String json = ChatMessageSerializer.messagesToJson(toKeep);
        memoryStore.save(new ChatMemoryDto(id, json, null));
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public List<Long> getTimestamps(String sessionId) {
        return memoryStore.getTimestamps(sessionId);
    }

    @Override
    @Transactional
    public void deleteMessages(Object memoryId) {
        String id = String.valueOf(memoryId);
        memoryStore.deleteById(id);
    }
}
