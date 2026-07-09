package com.amsha.selfevolve.api;

import java.time.Instant;
import java.util.UUID;

import org.jdbi.v3.core.mapper.reflect.ColumnName;

/**
 * A behavioral directive synthesized from extracted memory patterns.
 * Immutable once created. Lifecycle state transitions create new instances.
 *
 * @param id                   unique identifier
 * @param userId               user this directive applies to
 * @param text                 directive text (max 600 chars)
 * @param state                lifecycle state
 * @param confidence           confidence score 0.0-1.0 (min 0.8 for ACTIVE injection)
 * @param sourceRecordIdsJson  comma-separated extracted memory IDs that led to this directive
 * @param synthesisPromptHash  hash of the prompt used for synthesis (provenance)
 * @param evaluationHits       number of sessions where SHADOW directive was evaluated positively
 * @param evaluationMisses     number of sessions where SHADOW directive was evaluated negatively
 * @param createdAt            creation timestamp
 * @param updatedAt            last state change timestamp
 */
public record SynthesizedDirective(
        String id,
        String userId,
        String text,
        DirectiveState state,
        double confidence,
        @ColumnName("source_record_ids") String sourceRecordIdsJson,
        String synthesisPromptHash,
        int evaluationHits,
        int evaluationMisses,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * Creates a new directive in CANDIDATE state.
     */
    public static SynthesizedDirective create(
            String userId, String text, double confidence, String sourceRecordIdsJson, String synthesisPromptHash) {
        Instant now = Instant.now();
        return new SynthesizedDirective(
                UUID.randomUUID().toString(),
                userId,
                text,
                DirectiveState.CANDIDATE,
                confidence,
                sourceRecordIdsJson,
                synthesisPromptHash,
                0,
                0,
                now,
                now);
    }

    /**
     * Returns a new directive with the updated state and timestamp.
     */
    public SynthesizedDirective withState(DirectiveState newState) {
        return new SynthesizedDirective(
                id,
                userId,
                text,
                newState,
                confidence,
                sourceRecordIdsJson,
                synthesisPromptHash,
                evaluationHits,
                evaluationMisses,
                createdAt,
                Instant.now());
    }

    /**
     * Returns a new directive with incremented evaluation hit.
     */
    public SynthesizedDirective withHit() {
        return new SynthesizedDirective(
                id,
                userId,
                text,
                state,
                confidence,
                sourceRecordIdsJson,
                synthesisPromptHash,
                evaluationHits + 1,
                evaluationMisses,
                createdAt,
                Instant.now());
    }

    /**
     * Returns a new directive with incremented evaluation miss.
     */
    public SynthesizedDirective withMiss() {
        return new SynthesizedDirective(
                id,
                userId,
                text,
                state,
                confidence,
                sourceRecordIdsJson,
                synthesisPromptHash,
                evaluationHits,
                evaluationMisses + 1,
                createdAt,
                Instant.now());
    }

    /**
     * Returns true if this directive is eligible for prompt injection.
     * Only ACTIVE and SHADOW states are injectable; SHADOW requires confidence >= 0.8.
     */
    public boolean isInjectable() {
        return switch (state) {
            case ACTIVE -> confidence >= 0.8;
            case SHADOW -> confidence >= 0.8;
            case CANDIDATE, DISABLED -> false;
        };
    }

    /**
     * Returns true if this directive is in SHADOW state and has enough evaluations to promote.
     */
    public boolean isReadyForPromotion(int minHits) {
        return state == DirectiveState.SHADOW && evaluationHits >= minHits;
    }

    public SynthesizedDirective {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        if (text.length() > 600) {
            throw new IllegalArgumentException("text must not exceed 600 chars");
        }
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0.0, 1.0]");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
        if (updatedAt == null) {
            throw new IllegalArgumentException("updatedAt must not be null");
        }
    }
}
