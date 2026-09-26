package com.amsha.core.classifier.remote;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;
import com.amsha.classifier.api.DecisionPayloadDecoder;
import com.amsha.classifier.api.DecisionQuestion;
import com.amsha.classifier.api.DecisionRequest;
import com.amsha.classifier.api.DecisionResult;
import com.amsha.core.security.http.OutboundHttpException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Encapsulates TypeSafe-compatible wire request serialization, response decoding,
 * and outbound error mapping for {@link RemoteDecisionEngine}.
 */
final class RemoteDecisionWireCodec {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private RemoteDecisionWireCodec() {}

    static String buildWireRequestBody(ObjectMapper mapper, DecisionRequest request, String projectedStateJson)
            throws JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decisionId", request.decisionId());
        payload.put("schemaVersion", request.schemaVersion());
        payload.put("state", projectedStateJson);

        Map<String, Object> questionsMap = new LinkedHashMap<>();
        for (Map.Entry<String, DecisionQuestion<?>> entry : request.questions().entrySet()) {
            DecisionQuestion<?> q = entry.getValue();
            Map<String, Object> qData = new LinkedHashMap<>();
            qData.put("primitive", q.key().primitive().name());
            qData.put("criteria", q.criteria());
            if (!q.options().isEmpty()) {
                qData.put("options", q.options());
            }
            if (!q.rubricLevels().isEmpty()) {
                qData.put("rubricLevels", q.rubricLevels());
            }
            questionsMap.put(entry.getKey(), qData);
        }
        payload.put("questions", questionsMap);
        payload.put("timeoutMs", request.timeout().toMillis());

        return mapper.writeValueAsString(payload);
    }

    static DecisionResult handleHttpResponse(
            ObjectMapper mapper, DecisionRequest request, HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == 200) {
            String body = response.body();
            if (body == null || body.isBlank()) {
                return DecisionResult.NoOpinion.ofMalformedResponse(
                        request.decisionId(), request.schemaVersion(), null, "Remote provider returned empty body");
            }
            try {
                Map<String, Object> rawMap = mapper.readValue(body, MAP_TYPE);
                return DecisionPayloadDecoder.decode(request, rawMap);
            } catch (JsonProcessingException _) {
                return DecisionResult.NoOpinion.ofMalformedResponse(
                        request.decisionId(), request.schemaVersion(), null, "Failed to parse provider response JSON");
            }
        }
        if (status == 401 || status == 403) {
            return new DecisionResult.NoOpinion(
                    request.decisionId(),
                    request.schemaVersion(),
                    NoOpinionReason.INFERENCE_ERROR,
                    null,
                    "Remote provider authentication or authorization failed");
        }
        if (status == 404) {
            return new DecisionResult.NoOpinion(
                    request.decisionId(),
                    request.schemaVersion(),
                    NoOpinionReason.MISSING_MODEL,
                    null,
                    "Remote provider model or endpoint not found (status 404)");
        }
        if (status == 408) {
            return new DecisionResult.NoOpinion(
                    request.decisionId(),
                    request.schemaVersion(),
                    NoOpinionReason.TIMEOUT,
                    null,
                    "Remote provider returned timeout status 408");
        }
        if (status == 503 || status == 429) {
            return new DecisionResult.NoOpinion(
                    request.decisionId(),
                    request.schemaVersion(),
                    NoOpinionReason.OVERLOAD_REJECTED,
                    null,
                    "Remote provider returned overload/unavailable status " + status);
        }
        if (status == 502 || status == 504) {
            return new DecisionResult.NoOpinion(
                    request.decisionId(),
                    request.schemaVersion(),
                    NoOpinionReason.MISSING_MODEL,
                    null,
                    "Remote provider gateway or upstream error (status " + status + ")");
        }
        return DecisionResult.NoOpinion.ofMalformedResponse(
                request.decisionId(),
                request.schemaVersion(),
                null,
                "Remote provider error response with status " + status);
    }

    static DecisionResult mapOutboundException(DecisionRequest request, OutboundHttpException ex, Duration timeout) {
        return switch (ex.category()) {
            case POLICY_DENIED ->
                DecisionResult.NoOpinion.ofMalformedResponse(
                        request.decisionId(),
                        request.schemaVersion(),
                        null,
                        "Outbound policy denied egress for remote decision provider: " + ex.getMessage());
            case HITL_REQUIRED ->
                new DecisionResult.NoOpinion(
                        request.decisionId(),
                        request.schemaVersion(),
                        NoOpinionReason.OVERLOAD_REJECTED,
                        null,
                        "Outbound policy requires HITL approval; remote evaluation cannot self-authorize");
            case DNS_REBINDING, DNS_FAILURE ->
                new DecisionResult.NoOpinion(
                        request.decisionId(),
                        request.schemaVersion(),
                        NoOpinionReason.MISSING_MODEL,
                        null,
                        "Remote destination DNS safety validation failed");
            case TLS_TRUST ->
                new DecisionResult.NoOpinion(
                        request.decisionId(),
                        request.schemaVersion(),
                        NoOpinionReason.INFERENCE_ERROR,
                        null,
                        "Remote transport TLS trust establishment failed");
            case TIMEOUT ->
                new DecisionResult.NoOpinion(
                        request.decisionId(),
                        request.schemaVersion(),
                        NoOpinionReason.TIMEOUT,
                        null,
                        "Remote provider request timed out after " + timeout.toMillis() + "ms");
            case REDIRECT_DENIED, REDIRECT_LIMIT ->
                DecisionResult.NoOpinion.ofMalformedResponse(
                        request.decisionId(), request.schemaVersion(), null, "Remote redirect policy violation");
            case IO ->
                new DecisionResult.NoOpinion(
                        request.decisionId(),
                        request.schemaVersion(),
                        NoOpinionReason.MISSING_MODEL,
                        null,
                        "Remote provider transport error");
            default ->
                DecisionResult.NoOpinion.ofMalformedResponse(
                        request.decisionId(), request.schemaVersion(), null, "Remote provider outbound failure");
        };
    }
}
