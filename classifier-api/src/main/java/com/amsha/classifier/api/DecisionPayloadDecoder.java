package com.amsha.classifier.api;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Provider-neutral payload decoder and validator.
 * Transforms raw provider response structures into typed {@link DecisionResult} instances
 * while strictly enforcing contract invariants:
 * <ul>
 *   <li>Missing provider fields remain absent; never invent zero, false, approval, confidence,
 *       or uniform distributions.</li>
 *   <li>Malformed, partial, or wrong-type optional fields fail closed to
 *       {@link ClassifierResult.NoOpinionReason#INFERENCE_ERROR}.</li>
 *   <li>Unknown answer handles in the payload fail closed to
 *       {@link ClassifierResult.NoOpinionReason#INFERENCE_ERROR}.</li>
 *   <li>Wrong-primitive payloads fail closed to
 *       {@link ClassifierResult.NoOpinionReason#INFERENCE_ERROR}.</li>
 *   <li>Legacy payloads are mapped to typed choices without fabricated values.</li>
 * </ul>
 */
public final class DecisionPayloadDecoder {

    private static final Set<String> KNOWN_ENVELOPE_KEYS =
            Set.of("answers", "ruleId", "providerId", "modelId", "latencyMs", "inputTokens", "outputTokens");

    private DecisionPayloadDecoder() {}

    /**
     * Decodes and validates a raw response map against a registered {@link DecisionRequest}.
     *
     * @param request the expected decision request specification
     * @param raw the raw provider payload map
     * @return a typed {@link DecisionResult} (Opinion or NoOpinion with INFERENCE_ERROR)
     */
    public static DecisionResult decode(DecisionRequest request, Map<String, Object> raw) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (raw == null) {
            return DecisionResult.NoOpinion.ofMalformedResponse(
                    request.decisionId(), request.schemaVersion(), null, "Provider payload is null");
        }

        for (Object rawKeyObj : raw.keySet()) {
            if (!(rawKeyObj instanceof String)) {
                return DecisionResult.NoOpinion.ofMalformedResponse(
                        request.decisionId(), request.schemaVersion(), null, "Malformed non-string top-level key");
            }
        }

        String ruleId = parseString(raw.get("ruleId"));
        if (raw.containsKey("ruleId") && ruleId == null) {
            return DecisionResult.NoOpinion.ofMalformedResponse(
                    request.decisionId(), request.schemaVersion(), null, "Malformed ruleId");
        }

        DecisionMetadata metadata = parseMetadata(raw);
        if (metadata == null) {
            return DecisionResult.NoOpinion.ofMalformedResponse(
                    request.decisionId(), request.schemaVersion(), ruleId, "Malformed metadata fields");
        }

        // Check for legacy single-value payload: { "value": ..., "confidence": ... }
        if (raw.containsKey("value")
                && !raw.containsKey("answers")
                && request.questions().size() == 1) {
            DecisionQuestion<?> singleQ =
                    request.questions().values().iterator().next();
            if (singleQ.key().primitive() == DecisionPrimitive.CHOICE) {
                return decodeLegacyPayload(request, singleQ, raw, ruleId, metadata);
            }
        }

        boolean nested = raw.containsKey("answers");
        Map<String, Object> answersMap = extractAnswersMap(raw);
        if (answersMap == null) {
            return DecisionResult.NoOpinion.ofMalformedResponse(
                    request.decisionId(), request.schemaVersion(), ruleId, "Payload missing answers container");
        }

        // Reject unknown handles (full-payload reference validation)
        for (Object submittedKeyObj : answersMap.keySet()) {
            if (!(submittedKeyObj instanceof String submittedKey)) {
                return DecisionResult.NoOpinion.ofMalformedResponse(
                        request.decisionId(),
                        request.schemaVersion(),
                        ruleId,
                        "Malformed non-string answer handle in payload");
            }
            if (nested) {
                if (!request.questions().containsKey(submittedKey)) {
                    return DecisionResult.NoOpinion.ofMalformedResponse(
                            request.decisionId(),
                            request.schemaVersion(),
                            ruleId,
                            "Unknown answer handle in answers payload: '" + submittedKey + "'");
                }
            } else {
                if (!request.questions().containsKey(submittedKey) && !KNOWN_ENVELOPE_KEYS.contains(submittedKey)) {
                    return DecisionResult.NoOpinion.ofMalformedResponse(
                            request.decisionId(),
                            request.schemaVersion(),
                            ruleId,
                            "Unknown question handle in top-level payload: '" + submittedKey + "'");
                }
            }
        }

        DecisionResult.OpinionBuilder builder =
                DecisionResult.Opinion.opinionBuilder(request.decisionId(), request.schemaVersion());
        builder.ruleId(ruleId);
        builder.metadata(metadata);

        for (Map.Entry<String, DecisionQuestion<?>> entry : request.questions().entrySet()) {
            String handleName = entry.getKey();
            DecisionQuestion<?> question = entry.getValue();

            if (!answersMap.containsKey(handleName)) {
                return DecisionResult.NoOpinion.ofMalformedResponse(
                        request.decisionId(),
                        request.schemaVersion(),
                        ruleId,
                        "Partial payload: missing answer for handle '" + handleName + "'");
            }

            Object rawAnswer = answersMap.get(handleName);
            DecisionAnswer answer = decodeAnswer(question, rawAnswer);
            if (answer == null) {
                return DecisionResult.NoOpinion.ofMalformedResponse(
                        request.decisionId(),
                        request.schemaVersion(),
                        ruleId,
                        "Malformed or wrong-primitive answer for handle '" + handleName + "'");
            }
            putTyped(builder, question.key(), answer);
        }

        try {
            return builder.build();
        } catch (Exception e) {
            return DecisionResult.NoOpinion.ofMalformedResponse(
                    request.decisionId(),
                    request.schemaVersion(),
                    ruleId,
                    "Failed to assemble opinion: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static <A extends DecisionAnswer> void putTyped(
            DecisionResult.OpinionBuilder builder, QuestionKey<A> key, DecisionAnswer answer) {
        builder.put(key, key.cast(answer));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractAnswersMap(Map<String, Object> raw) {
        Object answersObj = raw.get("answers");
        if (answersObj instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        if (answersObj != null) {
            // "answers" exists but is not a Map (e.g. wrong type) -> invalid
            return null;
        }
        return raw;
    }

    private static DecisionResult decodeLegacyPayload(
            DecisionRequest request,
            DecisionQuestion<?> question,
            Map<String, Object> raw,
            String ruleId,
            DecisionMetadata metadata) {
        Object valObj = raw.get("value");
        if (valObj == null) {
            return DecisionResult.NoOpinion.ofMalformedResponse(
                    request.decisionId(), request.schemaVersion(), ruleId, "Legacy payload missing value");
        }
        String label = valObj.toString();
        if (label.isBlank() || !question.options().contains(label)) {
            return DecisionResult.NoOpinion.ofMalformedResponse(
                    request.decisionId(),
                    request.schemaVersion(),
                    ruleId,
                    "Legacy label '" + label + "' not in allowed options");
        }

        OptionalDouble confidence = OptionalDouble.empty();
        if (raw.containsKey("confidence")) {
            Double confVal = parseDouble(raw.get("confidence"));
            if (confVal == null || confVal < 0.0 || confVal > 1.0) {
                return DecisionResult.NoOpinion.ofMalformedResponse(
                        request.decisionId(), request.schemaVersion(), ruleId, "Legacy confidence out of bounds");
            }
            confidence = OptionalDouble.of(confVal);
        }

        Choice choice = new Choice(label, Map.of(), confidence);
        DecisionResult.OpinionBuilder builder =
                DecisionResult.Opinion.opinionBuilder(request.decisionId(), request.schemaVersion());
        builder.ruleId(ruleId);
        builder.metadata(metadata);
        putTyped(builder, question.key(), choice);
        return builder.build();
    }

    private static DecisionAnswer decodeAnswer(DecisionQuestion<?> question, Object raw) {
        if (raw == null) {
            return null;
        }
        DecisionPrimitive primitive = question.key().primitive();
        switch (primitive) {
            case CHOICE -> {
                return decodeChoice(question, raw);
            }
            case SCORE -> {
                return decodeScore(question, raw);
            }
            case NOUL -> {
                return decodeNoul(raw);
            }
            default -> {
                return null;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Choice decodeChoice(DecisionQuestion<?> question, Object raw) {
        if (raw instanceof Number) {
            return null;
        }
        String label = null;
        OptionalDouble confidence = OptionalDouble.empty();
        Map<String, Double> probabilities = Map.of();

        if (raw instanceof String str) {
            label = str;
        } else if (raw instanceof Map<?, ?> map) {
            Object choiceObj = map.containsKey("choice") ? map.get("choice") : map.get("label");
            if (choiceObj instanceof String s) {
                label = s;
            }
            if (map.containsKey("confidence")) {
                Double c = parseDouble(map.get("confidence"));
                if (c == null || c < 0.0 || c > 1.0) {
                    return null;
                }
                confidence = OptionalDouble.of(c);
            }
            if (map.containsKey("probabilities")) {
                Object probObj = map.get("probabilities");
                if (!(probObj instanceof Map<?, ?> probMap)) {
                    return null;
                }
                Map<String, Double> parsed = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : probMap.entrySet()) {
                    if (!(entry.getKey() instanceof String opt) || opt.isBlank()) {
                        return null;
                    }
                    Double p = parseDouble(entry.getValue());
                    if (p == null || p < 0.0 || p > 1.0) {
                        return null;
                    }
                    parsed.put(opt, p);
                }
                probabilities = parsed;
            }
        }

        if (label == null || label.isBlank()) {
            return null;
        }
        if (!question.options().isEmpty() && !question.options().contains(label)) {
            return null;
        }
        try {
            return new Choice(label, probabilities, confidence);
        } catch (Exception _) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Score decodeScore(DecisionQuestion<?> question, Object raw) {
        if (raw instanceof String) {
            return null;
        }
        Double score = null;
        OptionalDouble confidence = OptionalDouble.empty();
        List<Double> probabilities = List.of();
        List<String> legend = List.of();

        if (raw instanceof Number num) {
            score = num.doubleValue();
        } else if (raw instanceof Map<?, ?> map) {
            if (map.containsKey("score")) {
                score = parseDouble(map.get("score"));
                if (score == null) {
                    return null;
                }
            }
            if (map.containsKey("confidence")) {
                Double c = parseDouble(map.get("confidence"));
                if (c == null || c < 0.0 || c > 1.0) {
                    return null;
                }
                confidence = OptionalDouble.of(c);
            }
            if (map.containsKey("probabilities")) {
                Object probObj = map.get("probabilities");
                if (!(probObj instanceof List<?> list)) {
                    return null;
                }
                List<Double> parsed = new ArrayList<>(list.size());
                for (Object item : list) {
                    Double p = parseDouble(item);
                    if (p == null || p < 0.0 || p > 1.0) {
                        return null;
                    }
                    parsed.add(p);
                }
                probabilities = parsed;
            }
            if (map.containsKey("legend")) {
                Object legObj = map.get("legend");
                if (!(legObj instanceof List<?> list)) {
                    return null;
                }
                List<String> parsed = new ArrayList<>(list.size());
                for (Object item : list) {
                    if (!(item instanceof String s) || s.isBlank()) {
                        return null;
                    }
                    parsed.add(s);
                }
                legend = parsed;
            }
        }

        if (score == null || !Double.isFinite(score)) {
            return null;
        }
        if (legend.isEmpty() && !question.rubricLevels().isEmpty()) {
            legend = question.rubricLevels();
        }
        try {
            return new Score(score, probabilities, legend, confidence);
        } catch (Exception _) {
            return null;
        }
    }

    private static Noul decodeNoul(Object raw) {
        if (raw instanceof String) {
            return null;
        }
        Double prob = null;
        if (raw instanceof Number num) {
            prob = num.doubleValue();
        } else if (raw instanceof Map<?, ?> map) {
            if (map.containsKey("choice") || map.containsKey("score")) {
                return null;
            }
            if (!map.containsKey("probability") && !map.containsKey("value")) {
                return null;
            }
            Object pObj = map.containsKey("probability") ? map.get("probability") : map.get("value");
            prob = parseDouble(pObj);
        }
        if (prob == null || !Double.isFinite(prob) || prob < 0.0 || prob > 1.0) {
            return null;
        }
        try {
            return new Noul(prob);
        } catch (Exception _) {
            return null;
        }
    }

    private static DecisionMetadata parseMetadata(Map<String, Object> raw) {
        String provider = null;
        if (raw.containsKey("providerId")) {
            provider = parseString(raw.get("providerId"));
            if (provider == null) {
                return null;
            }
        }

        String model = null;
        if (raw.containsKey("modelId")) {
            model = parseString(raw.get("modelId"));
            if (model == null) {
                return null;
            }
        }

        Duration latency = null;
        if (raw.containsKey("latencyMs")) {
            Double ms = parseDouble(raw.get("latencyMs"));
            if (ms == null || ms < 0.0) {
                return null;
            }
            latency = Duration.ofMillis(ms.longValue());
        }

        Long inTokens = null;
        if (raw.containsKey("inputTokens")) {
            inTokens = parseLong(raw.get("inputTokens"));
            if (inTokens == null || inTokens < 0) {
                return null;
            }
        }

        Long outTokens = null;
        if (raw.containsKey("outputTokens")) {
            outTokens = parseLong(raw.get("outputTokens"));
            if (outTokens == null || outTokens < 0) {
                return null;
            }
        }

        return new DecisionMetadata(provider, model, latency, inTokens, outTokens);
    }

    private static String parseString(Object obj) {
        if (obj instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        return null;
    }

    private static Double parseDouble(Object obj) {
        if (obj instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        if (obj instanceof String s) {
            try {
                double d = Double.parseDouble(s.trim());
                return Double.isFinite(d) ? d : null;
            } catch (NumberFormatException _) {
                return null;
            }
        }
        return null;
    }

    private static Long parseLong(Object obj) {
        if (obj instanceof Number n) {
            return n.longValue();
        }
        if (obj instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException _) {
                return null;
            }
        }
        return null;
    }
}
