package com.amsha.core.classifier.wire;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.amsha.classifier.api.DecisionQuestion;
import com.amsha.classifier.api.DecisionRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Maps an Amsha {@link DecisionRequest} onto the System One {@code POST /v1/systemone} wire contract
 * shared by TypeSafe Jev, Laya, Kev, and CLM.
 *
 * <p>Two vocabularies collide here and must not be conflated:
 * <ul>
 *   <li>Amsha's {@link DecisionQuestion#criteria()} is the free-text instruction and maps to the
 *       contract's {@code instructions}.</li>
 *   <li>The contract's {@code criteria} is the option set for a Choice (a map of option to optional
 *       rubric detail) or the ordered rubric for a Score (an array of 2–10 level descriptions), and
 *       maps from Amsha's {@code options} / {@code rubricLevels}.</li>
 * </ul>
 *
 * <p>Questions are discriminated by a lowercase {@code type}. Amsha-internal fields
 * ({@code decisionId}, {@code schemaVersion}, {@code timeoutMs}) are deliberately not emitted: they
 * are not part of the contract.
 *
 * <p>The route and the question vocabulary are shared by every candidate provider, but the
 * {@code state} field is not. {@link WireProfile} selects the dialect: {@link WireProfile#JSON_OBJECT}
 * sends the projection as a JSON string (TypeSafe, Laya, Kev), while {@link WireProfile#PROSE} renders
 * it as prose (CLM). Sending the wrong one is not rejected by the provider — it degrades answers
 * silently — which is why the profile is explicit and closed rather than defaulted per call site.
 *
 * <p>Mapping is total but not lossy-by-default: a question with no valid contract representation
 * yields {@link Outcome.Unrepresentable} so the caller fails closed instead of sending a request the
 * provider would reject with {@code 422}.
 */
public final class SystemOneWireMapper {

    private static final String TYPE_CHOICE = "choice";
    private static final String TYPE_SCORE = "score";
    private static final String TYPE_NOUL = "noul";

    /** The contract accepts a Score rubric of 2 to 10 ordered level descriptions. */
    private static final int MIN_SCORE_LEVELS = 2;

    private static final int MAX_SCORE_LEVELS = 10;

    private SystemOneWireMapper() {}

    /**
     * Outcome of mapping a decision request onto the System One contract.
     */
    public sealed interface Outcome {

        /**
         * A serialized request body ready to POST to {@code /v1/systemone}.
         *
         * @param body JSON request body
         */
        record Ready(String body) implements Outcome {}

        /**
         * The request has no valid contract representation. The caller must fail closed rather than
         * send a request the provider would reject.
         *
         * @param reason payload-free explanation
         */
        record Unrepresentable(String reason) implements Outcome {}
    }

    /**
     * Maps a decision request onto the System One wire contract using the
     * {@link WireProfile#JSON_OBJECT} dialect.
     *
     * <p>This overload exists for the TypeSafe remote lane, which is the only remote provider today
     * and always speaks JSON. Self-hosted callers should pass an explicit profile, because their
     * endpoint's dialect is a property of that endpoint rather than of the mapper.
     *
     * @param mapper Jackson mapper used for serialization
     * @param request decision request to map
     * @param projectedStateJson allow-listed, redacted state projection
     * @param modelId provider model identifier for the {@code model} field; when blank the field is
     *     omitted, which is correct for self-hosted servers that do not use it and would misread a
     *     hosted vendor's alias
     * @return a ready body, or an {@link Outcome.Unrepresentable} reason
     */
    public static Outcome map(ObjectMapper mapper, DecisionRequest request, String projectedStateJson, String modelId) {
        return map(mapper, request, projectedStateJson, modelId, WireProfile.JSON_OBJECT);
    }

    /**
     * Maps a decision request onto the System One wire contract for a specific provider dialect.
     *
     * @param mapper Jackson mapper used for serialization
     * @param request decision request to map
     * @param projectedStateJson allow-listed, redacted state projection
     * @param modelId provider model identifier for the {@code model} field; when blank the field is
     *     omitted
     * @param wireProfile how this provider expects {@code state} to be rendered
     * @return a ready body, or an {@link Outcome.Unrepresentable} reason
     */
    public static Outcome map(
            ObjectMapper mapper,
            DecisionRequest request,
            String projectedStateJson,
            String modelId,
            WireProfile wireProfile) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(projectedStateJson, "projectedStateJson");
        Objects.requireNonNull(wireProfile, "wireProfile");

        Map<String, Object> questions = new LinkedHashMap<>();
        for (Map.Entry<String, DecisionQuestion<?>> entry : request.questions().entrySet()) {
            Map<String, Object> mapped = mapQuestion(entry.getValue());
            if (mapped == null) {
                return new Outcome.Unrepresentable(
                        "Question '" + entry.getKey() + "' cannot be expressed in the System One contract");
            }
            questions.put(entry.getKey(), mapped);
        }

        String state;
        if (wireProfile == WireProfile.PROSE) {
            Optional<String> rendered = renderProse(mapper, projectedStateJson);
            if (rendered.isEmpty()) {
                return new Outcome.Unrepresentable("State projection cannot be rendered as prose for a PROSE provider");
            }
            state = rendered.get();
        } else {
            state = projectedStateJson;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("state", state);
        if (modelId != null && !modelId.isBlank()) {
            payload.put("model", modelId.trim());
        }
        payload.put("questions", questions);

        try {
            return new Outcome.Ready(mapper.writeValueAsString(payload));
        } catch (JsonProcessingException _) {
            return new Outcome.Unrepresentable("Failed to serialize the System One wire request");
        }
    }

    /**
     * Renders an already-projected state as prose.
     *
     * <p>Rendering happens here, after projection and redaction, so it cannot widen what leaves the
     * process: the input is the same string the {@link WireProfile#JSON_OBJECT} dialect would have
     * sent unchanged.
     *
     * @param mapper Jackson mapper used to parse the projection
     * @param projectedStateJson allow-listed, redacted state projection
     * @return the prose rendering, or empty when the projection is not JSON or carries no fields
     */
    private static Optional<String> renderProse(ObjectMapper mapper, String projectedStateJson) {
        JsonNode state;
        try {
            state = mapper.readTree(projectedStateJson);
        } catch (JsonProcessingException _) {
            return Optional.empty();
        }
        if (state == null || state.isMissingNode()) {
            return Optional.empty();
        }
        String rendered = ProseStateRenderer.render(state);
        return rendered.isBlank() ? Optional.empty() : Optional.of(rendered);
    }

    /**
     * Maps one question onto its contract form, or returns null when it has no valid representation.
     */
    private static Map<String, Object> mapQuestion(DecisionQuestion<?> question) {
        String instructions = question.criteria();
        if (instructions == null || instructions.isBlank()) {
            return null;
        }

        Map<String, Object> mapped = new LinkedHashMap<>();
        mapped.put("instructions", instructions);

        switch (question.key().primitive()) {
            case CHOICE -> {
                if (question.options().size() < 2) {
                    return null;
                }
                mapped.put("type", TYPE_CHOICE);
                mapped.put("criteria", choiceCriteria(question.options()));
            }
            case SCORE -> {
                List<String> levels = question.rubricLevels();
                if (levels.size() < MIN_SCORE_LEVELS || levels.size() > MAX_SCORE_LEVELS) {
                    return null;
                }
                mapped.put("type", TYPE_SCORE);
                mapped.put("criteria", levels);
            }
            case NOUL -> mapped.put("type", TYPE_NOUL);
            default -> {
                return null;
            }
        }
        return mapped;
    }

    /**
     * Builds the Choice criteria map. The contract permits null when an option needs no extra rubric
     * detail, and Amsha carries no per-option descriptions, so every option maps to null rather than
     * to invented text. This relies on the serializer emitting nulls (no {@code NON_NULL} inclusion);
     * {@code SystemOneWireMapperTest} pins that.
     */
    private static Map<String, Object> choiceCriteria(List<String> options) {
        Map<String, Object> criteria = new LinkedHashMap<>();
        for (String option : options) {
            criteria.put(option, null);
        }
        return criteria;
    }
}
