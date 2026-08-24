package com.amsha.core.state;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.reasoning.MessageTag;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.memory.config.TieredMemoryConfig;
import com.amsha.memory.tiered.RecallMemoryService;
import com.amsha.memory.tiered.RecallMessageMapper;
import com.amsha.persistence.api.ChatMemoryDto;
import com.amsha.persistence.api.ChatMemoryStorePort;
import com.amsha.persistence.api.RecallMessageDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;

/**
 * SQLite-backed implementation of LangChain4j {@link ChatMemoryStore}.
 * Refactored to delegate to {@link ChatMemoryStorePort}.
 * §1.2: Zero JPA imports, pure JDBI delegation.
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

    @Inject
    TieredMemoryConfig tieredConfig;

    @Inject
    RecallMemoryService recallService;

    @Override
    @Transactional
    public List<ChatMessage> getMessages(Object memoryId) {
        String id = String.valueOf(memoryId);

        // Guard against race conditions during async compaction (C2)
        compactionEventBridge.awaitIfInFlight(id);

        return memoryStore
                .findById(id)
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
            toKeep.addAll(nonSystemMessages.subList(
                    nonSystemMessages.size() - MAX_NON_SYSTEM_MESSAGES, nonSystemMessages.size()));
        } else {
            toKeep.addAll(nonSystemMessages);
        }

        // Timestamp handling (H1) — read from metadata
        ReadMetadata metadata = readMetadata(id);
        int lastCumulativeCount = metadata.cumulativeNonSystemCount;
        List<Long> currentTimestamps = metadata.timestamps;
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
        int currentWindowSize = nonSystemMessages.size();
        int newlyAdded = Math.max(0, nonSystemMessages.size() - currentTimestamps.size());
        int cumulativeCount = lastCumulativeCount + newlyAdded;
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("timestamps", updatedTimestamps);
            meta.put("cumulativeNonSystemCount", cumulativeCount);
            meta.put("windowSize", currentWindowSize);
            metadataJson = objectMapper.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            log.warnf("Failed to serialize metadata for session %s", id);
        }

        memoryStore.save(new ChatMemoryDto(id, json, metadataJson));

        // Persist all non-system messages; DB unique constraint handles dedupe
        int delta = cumulativeCount - lastCumulativeCount;
        if (delta > 0 && tieredConfig.enabled() && tieredConfig.recall().enabled()) {
            int startTurn = lastCumulativeCount;
            int tailStart = Math.max(0, nonSystemMessages.size() - delta);
            List<ChatMessage> newMessages = nonSystemMessages.subList(tailStart, nonSystemMessages.size());
            List<RecallMessageDto> dtos =
                    RecallMessageMapper.map(id, WorkspaceContext.getUserIdOrUnknown(), newMessages, startTurn);
            recallService.insertBatch(dtos);
        }
    }

    @Transactional
    public List<Long> getTimestamps(String sessionId) {
        return readMetadata(sessionId).timestamps;
    }

    private record ReadMetadata(List<Long> timestamps, int cumulativeNonSystemCount, int windowSize) {}

    private ReadMetadata readMetadata(String sessionId) {
        return memoryStore
                .findById(sessionId)
                .map(ChatMemoryDto::metadataJson)
                .map(json -> parseMetadata(json))
                .orElse(new ReadMetadata(Collections.emptyList(), 0, 0));
    }

    private ReadMetadata parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return new ReadMetadata(Collections.emptyList(), 0, 0);
        }
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            if (parsed instanceof List) {
                List<Long> timestamps = objectMapper.convertValue(parsed, new TypeReference<List<Long>>() {});
                return new ReadMetadata(timestamps, timestamps.size(), timestamps.size());
            }
            if (parsed instanceof Map map) {
                List<Long> timestamps = map.containsKey("timestamps")
                        ? objectMapper.convertValue(map.get("timestamps"), new TypeReference<List<Long>>() {})
                        : Collections.emptyList();
                int cumulativeCount = map.containsKey("cumulativeNonSystemCount")
                        ? ((Number) map.get("cumulativeNonSystemCount")).intValue()
                        : map.containsKey("nonSystemCount")
                                ? ((Number) map.get("nonSystemCount")).intValue()
                                : timestamps.size();
                int windowSize =
                        map.containsKey("windowSize") ? ((Number) map.get("windowSize")).intValue() : cumulativeCount;
                return new ReadMetadata(timestamps, cumulativeCount, windowSize);
            }
        } catch (Exception e) {
            log.warnf("Failed to parse metadata for session: %s", LogUtil.rootMessage(e));
        }
        return new ReadMetadata(Collections.emptyList(), 0, 0);
    }

    @Override
    @Transactional
    public void deleteMessages(Object memoryId) {
        String id = String.valueOf(memoryId);
        memoryStore.deleteById(id);
    }

    /**
     * Returns session metadata for compaction alignment.
     * @param sessionId the session ID
     * @return SessionMetadata with cumulativeNonSystemCount and windowSize
     */
    @Transactional
    public SessionMetadata getSessionMetadata(String sessionId) {
        ReadMetadata metadata = readMetadata(sessionId);
        return new SessionMetadata(metadata.cumulativeNonSystemCount, metadata.windowSize);
    }

    public record SessionMetadata(int cumulativeNonSystemCount, int windowSize) {}
}
