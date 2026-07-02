package com.amsha.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.amsha.core.memory.tiered.TieredBackend;
import com.amsha.persistence.api.RecallMemoryStorePort;
import com.amsha.persistence.api.RecallMessageDto;
import com.amsha.persistence.api.RecallSearchQuery;

import org.jboss.logging.Logger;

import io.quarkus.arc.properties.IfBuildProperty;

/**
 * Filesystem implementation of Recall Memory store.
 * Stores messages as JSON per session in {dataDir}/recall/{sessionId}.json.
 * Search uses case-insensitive substring matching (no FTS5 — FS backend is BM25-free).
 */
@ApplicationScoped
@TieredBackend("fs")
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "fs")
public class FsRecallMemoryStore implements RecallMemoryStorePort {

    private static final Logger log = Logger.getLogger(FsRecallMemoryStore.class);
    private static final String DOMAIN = "recall";

    record MessageList(List<RecallMessageDto> messages) {}

    @Inject
    FilesystemFileStore store;

    @Override
    public void insertOrIgnore(RecallMessageDto message) {
        List<RecallMessageDto> messages = loadMessages(message.sessionId());
        boolean exists = messages.stream()
                .anyMatch(m -> m.sessionId().equals(message.sessionId())
                        && m.turnNumber() == message.turnNumber()
                        && m.contentHash().equals(message.contentHash()));
        if (!exists) {
            messages.add(message);
            store.write(List.of(DOMAIN, message.sessionId() + ".json"), new MessageList(messages));
        }
    }

    @Override
    public void insertBatch(List<RecallMessageDto> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        // Group by session
        var bySession = new HashMap<String, List<RecallMessageDto>>();
        for (RecallMessageDto m : messages) {
            bySession.computeIfAbsent(m.sessionId(), k -> new ArrayList<>()).add(m);
        }
        for (var entry : bySession.entrySet()) {
            List<RecallMessageDto> existing = loadMessages(entry.getKey());
            Set<String> existingKeys = new HashSet<>();
            for (RecallMessageDto m : existing) {
                existingKeys.add(m.sessionId() + ":" + m.turnNumber() + ":" + m.contentHash());
            }
            for (RecallMessageDto m : entry.getValue()) {
                String key = m.sessionId() + ":" + m.turnNumber() + ":" + m.contentHash();
                if (!existingKeys.contains(key)) {
                    existing.add(m);
                    existingKeys.add(key);
                }
            }
            store.write(List.of(DOMAIN, entry.getKey() + ".json"), new MessageList(existing));
        }
        log.debugf("[FsRecall] Inserted %d messages across %d sessions", messages.size(), bySession.size());
    }

    @Override
    public List<RecallMessageDto> search(RecallSearchQuery query) {
        String q = query.query() != null ? query.query().toLowerCase(Locale.ROOT) : "";
        boolean isCrossSession = query.isCrossSession();

        List<RecallMessageDto> results = new ArrayList<>();

        if (isCrossSession) {
            // Search all sessions
            for (String f : store.list(List.of(DOMAIN), ".json")) {
                for (RecallMessageDto m : loadMessagesFromFile(f)) {
                    if (matchesQuery(m, query, q)) {
                        results.add(m);
                    }
                }
            }
        } else {
            // Search specific session
            for (RecallMessageDto m : loadMessages(query.sessionId())) {
                if (matchesQuery(m, query, q)) {
                    results.add(m);
                }
            }
        }

        // Sort by turn number descending (most recent first)
        results.sort((a, b) -> Integer.compare(b.turnNumber(), a.turnNumber()));

        if (query.limit() > 0 && results.size() > query.limit()) {
            return results.subList(0, query.limit());
        }
        return results;
    }

    @Override
    public Optional<RecallMessageDto> getById(String messageId) {
        // Search all sessions for the message
        for (String f : store.list(List.of(DOMAIN), ".json")) {
            for (RecallMessageDto m : loadMessagesFromFile(f)) {
                if (m.messageId().equals(messageId)) {
                    return Optional.of(m);
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public void purgeExpired(String userId, int retentionDays) {
        // No-op: recall messages are ephemeral per-session
    }

    private boolean matchesQuery(RecallMessageDto m, RecallSearchQuery query, String lowerQuery) {
        // User filter
        if (query.userId() != null && !query.userId().equals(m.userId())) {
            return false;
        }
        // Summary filter
        if (!query.includeSummaries() && "COMPACTION_SUMMARY".equals(m.messageType())) {
            return false;
        }
        // Message type filter
        if (query.messageTypes() != null
                && !query.messageTypes().isEmpty()
                && !query.messageTypes().contains(m.messageType())) {
            return false;
        }
        // Text search (case-insensitive substring)
        if (!lowerQuery.isEmpty()
                && m.content() != null
                && !m.content().toLowerCase(Locale.ROOT).contains(lowerQuery)) {
            return false;
        }
        return true;
    }

    private List<RecallMessageDto> loadMessages(String sessionId) {
        return loadMessagesFromFile(sessionId + ".json");
    }

    private List<RecallMessageDto> loadMessagesFromFile(String filename) {
        Optional<MessageList> list = store.readOptional(List.of(DOMAIN, filename), MessageList.class);
        return list.map(m -> new ArrayList<>(m.messages())).orElseGet(ArrayList::new);
    }
}
