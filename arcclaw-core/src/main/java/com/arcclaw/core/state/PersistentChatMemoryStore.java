package com.arcclaw.core.state;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import com.arcclaw.core.agent.reasoning.MessageTag;
import com.arcclaw.persistence.api.ChatMemoryDto;
import com.arcclaw.persistence.api.ChatMemoryStorePort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.quarkus.arc.Unremovable;
import org.jboss.logging.Logger;

/**
 * SQLite-backed implementation of LangChain4j {@link ChatMemoryStore}.
 * Refactored to delegate to {@link ChatMemoryStorePort}.
 * §1.2: Zero EntityManager imports, zero JPQL.
 * 
 * Logic (§8): Implements a rolling window that preserves all SystemMessages
 * and keeps the last 20 non-system messages (User/AI/Tool).
 */
@ApplicationScoped
@Alternative
@Priority(1)
@Unremovable
public class PersistentChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = Logger.getLogger(PersistentChatMemoryStore.class);
    private static final int MAX_NON_SYSTEM_MESSAGES = 20;

    @Inject
    ChatMemoryStorePort memoryStore;

    @Inject
    CompactionEventBridge compactionEventBridge;

    @Inject
    ObjectMapper objectMapper;

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<ChatMessage> getMessages(Object memoryId) {
        String id = String.valueOf(memoryId);
        
        // Guard against race conditions during async compaction (C2)
        compactionEventBridge.awaitIfInFlight(id);
        
        return memoryStore.findById(id)
                .map(dto -> ChatMessageDeserializer.messagesFromJson(dto.messagesJson()))
                .orElse(Collections.emptyList());
    }

    @Override
    @Transactional
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String id = String.valueOf(memoryId);
        
        // Filter out compliance-injected system messages (H4)
        List<ChatMessage> filteredMessages = messages.stream()
                .filter(msg -> {
                    if (!(msg instanceof AiMessage ai)) return true;
                    return !MessageTag.isTagged(ai.text());
                })
                .collect(Collectors.toList());

        List<ChatMessage> systemMessages = filteredMessages.stream()
                .filter(m -> m instanceof SystemMessage)
                .collect(Collectors.toList());
                
        List<ChatMessage> nonSystemMessages = filteredMessages.stream()
                .filter(m -> !(m instanceof SystemMessage))
                .collect(Collectors.toList());
                
        List<ChatMessage> toKeep = new ArrayList<>(systemMessages);
        if (nonSystemMessages.size() > MAX_NON_SYSTEM_MESSAGES) {
            toKeep.addAll(nonSystemMessages.subList(nonSystemMessages.size() - MAX_NON_SYSTEM_MESSAGES, nonSystemMessages.size()));
        } else {
            toKeep.addAll(nonSystemMessages);
        }
        
        // Timestamp handling (H1)
        List<Long> currentTimestamps = getTimestamps(id);
        List<Long> updatedTimestamps = new ArrayList<>();
        long now = System.currentTimeMillis();
        
        // Adjust timestamps to match the new size, padding with 'now' for any new messages
        for (int i = 0; i < toKeep.size(); i++) {
            if (i < currentTimestamps.size()) {
                updatedTimestamps.add(currentTimestamps.get(i));
            } else {
                updatedTimestamps.add(now);
            }
        }
        
        String json = ChatMessageSerializer.messagesToJson(toKeep);
        String metadataJson = null;
        try {
            metadataJson = objectMapper.writeValueAsString(updatedTimestamps);
        } catch (JsonProcessingException e) {
            log.warnf("Failed to serialize timestamps for session %s", id);
        }
        
        memoryStore.save(new ChatMemoryDto(id, json, metadataJson));
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public List<Long> getTimestamps(String sessionId) {
        return memoryStore.findById(sessionId)
                .map(ChatMemoryDto::metadataJson)
                .map(json -> {
                    if (json == null || json.isBlank()) return Collections.<Long>emptyList();
                    try {
                        return objectMapper.readValue(json, new TypeReference<List<Long>>() {});
                    } catch (JsonProcessingException e) {
                        return Collections.<Long>emptyList();
                    }
                })
                .orElse(Collections.emptyList());
    }

    @Override
    @Transactional
    public void deleteMessages(Object memoryId) {
        String id = String.valueOf(memoryId);
        memoryStore.deleteById(id);
    }
}
