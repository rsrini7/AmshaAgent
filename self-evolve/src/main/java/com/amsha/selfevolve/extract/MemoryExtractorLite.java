package com.amsha.selfevolve.extract;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import com.amsha.selfevolve.api.EvolutionEvent;
import com.amsha.selfevolve.api.EvolutionEventType;
import com.amsha.selfevolve.api.ExtractedMemory;
import com.amsha.selfevolve.api.ExtractionRule;
import com.amsha.selfevolve.api.MemoryRecordType;
import com.amsha.selfevolve.internal.ExtractionDeduplicator;

/**
 * Rule-based pattern matcher over evolution trace events (Phase 9).
 * Applies deterministic extraction rules to identify structured memory records
 * from raw interaction traces. No LLM dependency.
 * <p>
 * Extraction rules:
 * <ul>
 *   <li><b>Failure pattern</b>: Same tool errors 2+ times in a session</li>
 *   <li><b>Resolution pattern</b>: Tool success immediately after tool error</li>
 *   <li><b>Tool hint</b>: Successful tool execution with notable content</li>
 *   <li><b>Error context</b>: Tool error with detailed error content</li>
 *   <li><b>Self-correction</b>: SELF_CORRECTION event type</li>
 *   <li><b>Escalation</b>: ESCALATION event type</li>
 * </ul>
 */
@Singleton
public class MemoryExtractorLite {

    private static final Pattern USER_PREFERENCE_PATTERN = Pattern.compile(
            "(?i)\\b(?:prefer|preferences?|please use|always use|never use|do not use|don't use|avoid|use .{1,80}? over)\\b");

    private final ExtractionDeduplicator deduplicator;
    private final List<ExtractionRule> rules;

    @Inject
    public MemoryExtractorLite(ExtractionDeduplicator deduplicator) {
        this.deduplicator = deduplicator;
        this.rules = List.of(
                this::extractUserPreferences,
                this::extractFailurePatterns,
                this::extractResolutionPatterns,
                this::extractToolHints,
                this::extractErrorContexts,
                this::extractSelfCorrections,
                this::extractEscalations);
    }

    /**
     * Runs all extraction rules against a session's events.
     *
     * @param events all evolution events for the session, ordered by creation time
     * @param userId the user who owns the session
     * @return list of extracted memories (duplicates filtered by content hash)
     */
    public List<ExtractedMemory> extract(List<EvolutionEvent> events, String userId) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }

        // Derive session ID from the first event
        String sessionId = events.getFirst().sessionId();

        // Apply all rules
        List<ExtractedMemory> allExtractions = new ArrayList<>();
        for (ExtractionRule rule : rules) {
            allExtractions.addAll(rule.apply(events, userId));
        }

        // Deduplicate by content hash within this extraction batch
        return deduplicateBatch(allExtractions, sessionId, userId);
    }

    /**
     * User preference: explicit preference/constraint statements from user messages.
     */
    private List<ExtractedMemory> extractUserPreferences(List<EvolutionEvent> events, String userId) {
        String sessionId = events.getFirst().sessionId();
        List<ExtractedMemory> results = new ArrayList<>();
        for (EvolutionEvent event : events) {
            if (event.eventType() != EvolutionEventType.USER_MESSAGE || event.content() == null) {
                continue;
            }
            String content = event.content().strip();
            String matchTarget = content.length() > 500 ? content.substring(0, 500) : content;
            if (content.length() < 8
                    || !USER_PREFERENCE_PATTERN.matcher(matchTarget).find()) {
                continue;
            }
            String normalized = normalizePreference(content);
            if (normalized.isBlank()) {
                continue;
            }
            String hash = deduplicator.hashContent(normalized);
            results.add(ExtractedMemory.create(
                    sessionId,
                    userId,
                    MemoryRecordType.USER_PREFERENCE,
                    normalized,
                    0.85,
                    hash,
                    List.of(event.eventId())));
        }
        return results;
    }

    /**
     * Failure pattern: Same tool errors 2+ times in a session.
     */
    private List<ExtractedMemory> extractFailurePatterns(List<EvolutionEvent> events, String userId) {
        Map<String, List<EvolutionEvent>> toolErrors = new HashMap<>();
        for (EvolutionEvent event : events) {
            if (event.eventType() == EvolutionEventType.TOOL_ERROR && event.toolName() != null) {
                toolErrors
                        .computeIfAbsent(event.toolName(), k -> new ArrayList<>())
                        .add(event);
            }
        }

        String sessionId = events.getFirst().sessionId();
        List<ExtractedMemory> results = new ArrayList<>();
        for (Map.Entry<String, List<EvolutionEvent>> entry : toolErrors.entrySet()) {
            if (entry.getValue().size() >= 2) {
                String content = "Tool '" + entry.getKey() + "' failed "
                        + entry.getValue().size()
                        + " times in session " + sessionId + ". Last error: "
                        + entry.getValue().getLast().content();
                String hash = deduplicator.hashContent(content);
                List<String> sourceIds =
                        entry.getValue().stream().map(EvolutionEvent::eventId).toList();
                results.add(ExtractedMemory.create(
                        sessionId, userId, MemoryRecordType.FAILURE_PATTERN, content, 0.7, hash, sourceIds));
            }
        }
        return results;
    }

    /**
     * Resolution pattern: Tool success immediately after tool error (same tool).
     */
    private List<ExtractedMemory> extractResolutionPatterns(List<EvolutionEvent> events, String userId) {
        String sessionId = events.getFirst().sessionId();
        List<ExtractedMemory> results = new ArrayList<>();
        for (int i = 0; i < events.size() - 1; i++) {
            EvolutionEvent current = events.get(i);
            EvolutionEvent next = events.get(i + 1);
            if (current.eventType() == EvolutionEventType.TOOL_ERROR
                    && next.eventType() == EvolutionEventType.TOOL_RESULT
                    && Objects.equals(current.toolName(), next.toolName())) {
                String content = "Tool '" + current.toolName() + "' recovered after error. Session: " + sessionId;
                String hash = deduplicator.hashContent(content);
                results.add(ExtractedMemory.create(
                        sessionId,
                        userId,
                        MemoryRecordType.RESOLUTION_PATTERN,
                        content,
                        0.8,
                        hash,
                        List.of(current.eventId(), next.eventId())));
            }
        }
        return results;
    }

    /**
     * Tool hint: Successful tool execution with notable content (non-empty result).
     */
    private List<ExtractedMemory> extractToolHints(List<EvolutionEvent> events, String userId) {
        String sessionId = events.getFirst().sessionId();
        List<ExtractedMemory> results = new ArrayList<>();
        for (EvolutionEvent event : events) {
            if (event.eventType() == EvolutionEventType.TOOL_RESULT
                    && event.toolName() != null
                    && event.content() != null
                    && event.content().length() > 20) {
                String content = "Tool '" + event.toolName() + "' produced result ("
                        + event.content().length() + " chars). Session: " + sessionId;
                String hash = deduplicator.hashContent(content);
                results.add(ExtractedMemory.create(
                        sessionId, userId, MemoryRecordType.TOOL_HINT, content, 0.5, hash, List.of(event.eventId())));
            }
        }
        return results;
    }

    /**
     * Error context: Tool error with substantial error content.
     */
    private List<ExtractedMemory> extractErrorContexts(List<EvolutionEvent> events, String userId) {
        String sessionId = events.getFirst().sessionId();
        List<ExtractedMemory> results = new ArrayList<>();
        for (EvolutionEvent event : events) {
            if (event.eventType() == EvolutionEventType.TOOL_ERROR
                    && event.content() != null
                    && event.content().length() > 30) {
                String content = "Error in tool '" + (event.toolName() != null ? event.toolName() : "unknown") + "': "
                        + event.content();
                String hash = deduplicator.hashContent(content);
                results.add(ExtractedMemory.create(
                        sessionId,
                        userId,
                        MemoryRecordType.ERROR_CONTEXT,
                        content,
                        0.6,
                        hash,
                        List.of(event.eventId())));
            }
        }
        return results;
    }

    /**
     * Self-correction: SELF_CORRECTION events.
     */
    private List<ExtractedMemory> extractSelfCorrections(List<EvolutionEvent> events, String userId) {
        String sessionId = events.getFirst().sessionId();
        List<ExtractedMemory> results = new ArrayList<>();
        for (EvolutionEvent event : events) {
            if (event.eventType() == EvolutionEventType.SELF_CORRECTION && event.content() != null) {
                String content = "Self-correction: " + event.content();
                String hash = deduplicator.hashContent(content);
                results.add(ExtractedMemory.create(
                        sessionId, userId, MemoryRecordType.WORKAROUND, content, 0.75, hash, List.of(event.eventId())));
            }
        }
        return results;
    }

    /**
     * Escalation: ESCALATION events.
     */
    private List<ExtractedMemory> extractEscalations(List<EvolutionEvent> events, String userId) {
        String sessionId = events.getFirst().sessionId();
        List<ExtractedMemory> results = new ArrayList<>();
        for (EvolutionEvent event : events) {
            if (event.eventType() == EvolutionEventType.ESCALATION && event.content() != null) {
                String content = "Escalation to role '" + (event.role() != null ? event.role() : "unknown") + "': "
                        + event.content();
                String hash = deduplicator.hashContent(content);
                results.add(ExtractedMemory.create(
                        sessionId,
                        userId,
                        MemoryRecordType.ACTIVE_TASK,
                        content,
                        0.65,
                        hash,
                        List.of(event.eventId())));
            }
        }
        return results;
    }

    /**
     * Deduplicates extracted memories within a batch by content hash.
     */
    private List<ExtractedMemory> deduplicateBatch(List<ExtractedMemory> extractions, String sessionId, String userId) {
        Map<String, ExtractedMemory> seen = new HashMap<>();
        for (ExtractedMemory mem : extractions) {
            String key = mem.recordType().name() + ":" + mem.contentHash();
            ExtractedMemory existing = seen.get(key);
            if (existing == null || mem.confidence() > existing.confidence()) {
                seen.put(key, mem);
            }
        }
        return List.copyOf(seen.values());
    }

    private static String normalizePreference(String content) {
        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.length() > 400) {
            normalized = normalized.substring(0, 400).trim();
        }
        return normalized;
    }
}
