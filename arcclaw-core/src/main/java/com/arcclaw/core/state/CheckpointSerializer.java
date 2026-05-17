package com.arcclaw.core.state;

import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.domain.model.AgentCheckpoint;
import com.arcclaw.common.util.LogUtil;
import com.arcclaw.persistence.api.ReasoningStepDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

/**
 * Pure serialization/deserialization service for {@link ReasoningStep} objects.
 *
 * <p>Owns the Jackson {@link ObjectMapper} configuration for all checkpoint
 * state JSON. No database access, no transactions, no side effects - any
 * caller may use it without transaction concerns.
 *
 * <p><strong>§9.1 (MUST):</strong> The {@code STEPS_TYPE} TypeReference is a
 * single static field - never constructed per call.
 */
@ApplicationScoped
public class CheckpointSerializer {

    private static final Logger log = Logger.getLogger(CheckpointSerializer.class);

    /** Static field - never re-constructed per call (§9.1). */
    private static final TypeReference<List<ReasoningStep>> STEPS_TYPE =
            new TypeReference<>() {};

    @Inject
    ObjectMapper objectMapper;

    // ── Checkpoint state JSON ─────────────────────────────────────────────────

    /**
     * Serializes reasoning steps to a JSON string for {@code stateJson} storage.
     */
    public String serializeSteps(List<ReasoningStep> steps) {
        if (steps == null || steps.isEmpty()) return "[]";
        try {
            return objectMapper.writerFor(STEPS_TYPE).writeValueAsString(steps);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Checkpoint serialization failed", e);
        }
    }

    /**
     * Deserializes {@code stateJson} from a checkpoint back to reasoning steps.
     *
     * <p>Returns an empty list on null/blank JSON or parse failure - never throws.
     */
    public List<ReasoningStep> deserializeSteps(AgentCheckpoint checkpoint) {
        if (checkpoint == null
                || checkpoint.getStateJson() == null
                || checkpoint.getStateJson().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(checkpoint.getStateJson(), STEPS_TYPE);
        } catch (JsonProcessingException e) {
            log.errorf("Failed to deserialize stateJson for checkpoint id=%d: %s",
                    checkpoint.getId(), LogUtil.rootMessage(e));
            log.debugf("Full stacktrace for deserialization failure of checkpoint id=%d:\n%s",
                    checkpoint.getId(), LogUtil.trimmedStack(e));
            return List.of();
        }
    }

    // ── DTO mapping ───────────────────────────────────────────────────────────

    /**
     * Maps a domain {@link ReasoningStep} to its persistence DTO.
     */
    public ReasoningStepDto toDto(ReasoningStep step) {
        return new ReasoningStepDto(
                serialize(step.thought()),
                step.action(),
                serialize(step.actionInput()),
                step.observation(),
                step.inputTokens(),
                step.outputTokens(),
                step.success(),
                step.errorCode(),
                step.traceId(),
                step.rootCauseClass()
        );
    }

    /**
     * Maps a persistence DTO back to a domain {@link ReasoningStep}.
     */
    public ReasoningStep fromDto(ReasoningStepDto dto) {
        return ReasoningStep.of(
                deserialize(dto.thoughtJson(), String.class),
                dto.action(),
                deserialize(dto.actionInputJson(), new TypeReference<Map<String, Object>>() {}),
                dto.observation(),
                dto.inputTokens(),
                dto.outputTokens(),
                dto.success(),
                dto.errorCode(),
                dto.traceId(),
                dto.rootCauseClass()
        );
    }

    // ── Generic helpers ───────────────────────────────────────────────────────

    /**
     * Serializes any object to JSON.
     * Throws {@link IllegalStateException} on failure - callers have no recovery
     * path if their data cannot be serialized.
     */
    public String serialize(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Checkpoint serialization failed", e);
        }
    }

    /**
     * Deserializes JSON to the given class. Returns {@code null} on blank/parse failure.
     */
    public <T> T deserialize(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * Deserializes JSON to a generic type. Returns {@code null} on blank/parse failure.
     */
    public <T> T deserialize(String json, TypeReference<T> typeRef) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
