package com.amsha.classifier.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic, network-free in-process implementation of {@link InProcessDecisionEngine}.
 * Operates strictly in-memory over immutable static answers, raw provider response maps,
 * or declarative failure simulation. Does not accept executable callbacks, ensuring zero
 * network I/O, zero persistence, and no side effects.
 *
 * <p>Enforces a defensive transaction boundary before evaluating: if invoked inside an active
 * JTA write transaction, evaluation fails fast and results are never returned (§5.9).
 */
public final class InMemoryDecisionEngine implements InProcessDecisionEngine {

    private final DecisionTransactionGuard transactionGuard;
    private final TransactionViolationPolicy transactionViolationPolicy;
    private final Map<QuestionKey<?>, DecisionAnswer> staticAnswers;
    private final Map<String, Object> defaultRawPayload;
    private final Map<String, Map<String, Object>> rawPayloadsByDecisionId;
    private final DecisionResult defaultResult;
    private final Map<String, DecisionResult> resultsByDecisionId;
    private final String ruleId;
    private final DecisionMetadata metadata;

    private InMemoryDecisionEngine(Builder builder) {
        this.transactionGuard = builder.transactionGuard;
        this.transactionViolationPolicy = builder.transactionViolationPolicy;
        this.staticAnswers = Map.copyOf(builder.staticAnswers);
        this.defaultRawPayload = deepCopyMap(builder.defaultRawPayload);
        Map<String, Map<String, Object>> copyRawMap = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : builder.rawPayloadsByDecisionId.entrySet()) {
            copyRawMap.put(entry.getKey(), deepCopyMap(entry.getValue()));
        }
        this.rawPayloadsByDecisionId = Collections.unmodifiableMap(copyRawMap);
        this.defaultResult = builder.defaultResult;
        this.resultsByDecisionId = Map.copyOf(builder.resultsByDecisionId);
        this.ruleId = builder.ruleId;
        this.metadata = builder.metadata != null ? builder.metadata : DecisionMetadata.empty();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static InMemoryDecisionEngine ofAnswers(Map<QuestionKey<?>, DecisionAnswer> answers) {
        return builder().withAnswers(answers).build();
    }

    public static InMemoryDecisionEngine ofRaw(Map<String, Object> rawPayload) {
        return builder().withRawPayload(rawPayload).build();
    }

    public static InMemoryDecisionEngine ofResult(DecisionResult result) {
        return builder().withResult(result).build();
    }

    public static InMemoryDecisionEngine ofNoOpinion(ClassifierResult.NoOpinionReason reason) {
        return builder().withSimulatedFailure(reason, null).build();
    }

    public static InMemoryDecisionEngine ofSimulatedTimeout() {
        return builder().withSimulatedTimeout().build();
    }

    @Override
    public DecisionResult evaluate(DecisionRequest request) {
        Objects.requireNonNull(request, "request");

        // Defensive transaction boundary: MUST NOT evaluate inside active JTA transaction (§5.9)
        if (transactionViolationPolicy == TransactionViolationPolicy.FAIL_FAST_EXCEPTION) {
            transactionGuard.ensureTransactionFree();
        } else if (transactionGuard.isInActiveTransaction()) {
            return new DecisionResult.NoOpinion(
                    request.decisionId(),
                    request.schemaVersion(),
                    ClassifierResult.NoOpinionReason.OVERLOAD_REJECTED,
                    null,
                    "Active JTA transaction detected: evaluation rejected by transaction boundary guard (§5.9)");
        }

        // 1. Specific decision result or default result (validated against request)
        DecisionResult specificResult = resultsByDecisionId.get(request.decisionId());
        if (specificResult != null) {
            return validateAndNormalizeResult(request, specificResult);
        }
        if (defaultResult != null) {
            return validateAndNormalizeResult(request, defaultResult);
        }

        // 2. Specific raw payload or default raw payload decoded via DecisionPayloadDecoder
        Map<String, Object> specificRaw = rawPayloadsByDecisionId.get(request.decisionId());
        if (specificRaw != null) {
            return DecisionPayloadDecoder.decode(request, specificRaw);
        }
        if (defaultRawPayload != null) {
            return DecisionPayloadDecoder.decode(request, defaultRawPayload);
        }

        // 3. Static answers matched against registered request questions
        if (!staticAnswers.isEmpty()) {
            Map<QuestionKey<?>, DecisionAnswer> answersForReq = new LinkedHashMap<>();
            for (DecisionQuestion<?> q : request.questions().values()) {
                DecisionAnswer ans = staticAnswers.get(q.key());
                if (ans == null) {
                    return DecisionResult.NoOpinion.ofMalformedResponse(
                            request.decisionId(),
                            request.schemaVersion(),
                            ruleId,
                            "Missing static answer for question handle: '"
                                    + q.key().name() + "'");
                }
                answersForReq.put(q.key(), ans);
            }
            return new DecisionResult.Opinion(
                    request.decisionId(), request.schemaVersion(), answersForReq, ruleId, metadata);
        }

        return new DecisionResult.NoOpinion(
                request.decisionId(),
                request.schemaVersion(),
                ClassifierResult.NoOpinionReason.DISABLED,
                ruleId,
                "No static answers, payloads, or results configured");
    }

    private DecisionResult validateAndNormalizeResult(DecisionRequest request, DecisionResult result) {
        if ("unspecified".equals(result.decisionId())) {
            if (result instanceof DecisionResult.NoOpinion noOp) {
                return new DecisionResult.NoOpinion(
                        request.decisionId(),
                        request.schemaVersion(),
                        noOp.reason(),
                        ruleId != null ? ruleId : noOp.ruleId(),
                        noOp.detail());
            }
            return DecisionResult.NoOpinion.ofMalformedResponse(
                    request.decisionId(),
                    request.schemaVersion(),
                    result.ruleId(),
                    "Configured Opinion cannot have 'unspecified' decisionId");
        }

        if (!result.decisionId().equals(request.decisionId())) {
            return DecisionResult.NoOpinion.ofMalformedResponse(
                    request.decisionId(),
                    request.schemaVersion(),
                    result.ruleId(),
                    "Result decisionId mismatch: expected '" + request.decisionId() + "', got '" + result.decisionId()
                            + "'");
        }

        if (result.schemaVersion() != request.schemaVersion()) {
            return DecisionResult.NoOpinion.ofMalformedResponse(
                    request.decisionId(),
                    request.schemaVersion(),
                    result.ruleId(),
                    "Result schemaVersion mismatch: expected " + request.schemaVersion() + ", got "
                            + result.schemaVersion());
        }

        if (result instanceof DecisionResult.Opinion op) {
            if (op.answers().size() != request.questions().size()) {
                return DecisionResult.NoOpinion.ofMalformedResponse(
                        request.decisionId(),
                        request.schemaVersion(),
                        op.ruleId(),
                        "Result answer count (" + op.answers().size() + ") does not match request question count ("
                                + request.questions().size() + ")");
            }
            for (QuestionKey<?> answerKey : op.answers().keySet()) {
                if (!request.contains(answerKey)) {
                    return DecisionResult.NoOpinion.ofMalformedResponse(
                            request.decisionId(),
                            request.schemaVersion(),
                            op.ruleId(),
                            "Result contains foreign or unknown question handle: '" + answerKey + "'");
                }
            }
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopyMap(Map<?, ?> original) {
        if (original == null) {
            return null;
        }
        Map<Object, Object> copy = new LinkedHashMap<>(original.size());
        for (Map.Entry<?, ?> entry : original.entrySet()) {
            copy.put(entry.getKey(), deepCopyValue(entry.getValue()));
        }
        return (Map<String, Object>) (Map<?, ?>) Collections.unmodifiableMap(copy);
    }

    private static List<Object> deepCopyList(List<?> original) {
        if (original == null) {
            return null;
        }
        List<Object> copy = new ArrayList<>(original.size());
        for (Object item : original) {
            copy.add(deepCopyValue(item));
        }
        return Collections.unmodifiableList(copy);
    }

    private static Object deepCopyValue(Object value) {
        if (value instanceof Map<?, ?> m) {
            return deepCopyMap(m);
        }
        if (value instanceof List<?> l) {
            return deepCopyList(l);
        }
        return value;
    }

    public DecisionTransactionGuard transactionGuard() {
        return transactionGuard;
    }

    public TransactionViolationPolicy transactionViolationPolicy() {
        return transactionViolationPolicy;
    }

    /**
     * Fluent builder for {@link InMemoryDecisionEngine}.
     */
    public static final class Builder {
        private DecisionTransactionGuard transactionGuard = DecisionTransactionGuard.system();
        private TransactionViolationPolicy transactionViolationPolicy = TransactionViolationPolicy.FAIL_FAST_EXCEPTION;
        private final Map<QuestionKey<?>, DecisionAnswer> staticAnswers = new LinkedHashMap<>();
        private Map<String, Object> defaultRawPayload;
        private final Map<String, Map<String, Object>> rawPayloadsByDecisionId = new LinkedHashMap<>();
        private DecisionResult defaultResult;
        private final Map<String, DecisionResult> resultsByDecisionId = new LinkedHashMap<>();
        private String ruleId;
        private DecisionMetadata metadata;

        private Builder() {}

        public Builder withTransactionGuard(DecisionTransactionGuard transactionGuard) {
            this.transactionGuard = Objects.requireNonNull(transactionGuard, "transactionGuard");
            return this;
        }

        public Builder withTransactionViolationPolicy(TransactionViolationPolicy transactionViolationPolicy) {
            this.transactionViolationPolicy =
                    Objects.requireNonNull(transactionViolationPolicy, "transactionViolationPolicy");
            return this;
        }

        public Builder withRawPayload(Map<String, Object> rawPayload) {
            Objects.requireNonNull(rawPayload, "rawPayload");
            this.defaultRawPayload = deepCopyMap(rawPayload);
            return this;
        }

        public Builder withRawPayload(String decisionId, Map<String, Object> rawPayload) {
            Objects.requireNonNull(decisionId, "decisionId");
            if (decisionId.isBlank()) {
                throw new IllegalArgumentException("decisionId must not be blank");
            }
            Objects.requireNonNull(rawPayload, "rawPayload");
            if (rawPayloadsByDecisionId.containsKey(decisionId)) {
                throw new IllegalArgumentException("Duplicate decision ID in raw payloads: '" + decisionId + "'");
            }
            this.rawPayloadsByDecisionId.put(decisionId, deepCopyMap(rawPayload));
            return this;
        }

        public Builder withResult(DecisionResult result) {
            this.defaultResult = Objects.requireNonNull(result, "result");
            return this;
        }

        public Builder withResult(String decisionId, DecisionResult result) {
            Objects.requireNonNull(decisionId, "decisionId");
            if (decisionId.isBlank()) {
                throw new IllegalArgumentException("decisionId must not be blank");
            }
            Objects.requireNonNull(result, "result");
            if (resultsByDecisionId.containsKey(decisionId)) {
                throw new IllegalArgumentException("Duplicate decision ID in results: '" + decisionId + "'");
            }
            this.resultsByDecisionId.put(decisionId, result);
            return this;
        }

        public Builder withSimulatedTimeout() {
            this.defaultResult = new DecisionResult.NoOpinion(
                    "unspecified", 1, ClassifierResult.NoOpinionReason.TIMEOUT, null, "Evaluation timed out");
            return this;
        }

        public Builder withSimulatedFailure(ClassifierResult.NoOpinionReason reason, String diagnostic) {
            Objects.requireNonNull(reason, "reason");
            this.defaultResult = new DecisionResult.NoOpinion(
                    "unspecified", 1, reason, null, diagnostic != null ? diagnostic : "Simulated failure");
            return this;
        }

        public <A extends DecisionAnswer> Builder withAnswer(QuestionKey<A> key, A answer) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(answer, "answer");
            if (staticAnswers.containsKey(key)) {
                throw new IllegalArgumentException("Duplicate question key in answers: '" + key + "'");
            }
            staticAnswers.put(key, key.cast(answer));
            return this;
        }

        public Builder withAnswers(Map<QuestionKey<?>, DecisionAnswer> answers) {
            Objects.requireNonNull(answers, "answers");
            for (Map.Entry<QuestionKey<?>, DecisionAnswer> entry : answers.entrySet()) {
                putAnswerEntry(entry.getKey(), entry.getValue());
            }
            return this;
        }

        private <A extends DecisionAnswer> void putAnswerEntry(QuestionKey<A> key, DecisionAnswer answer) {
            withAnswer(key, key.cast(answer));
        }

        public Builder withRuleId(String ruleId) {
            this.ruleId = ruleId;
            return this;
        }

        public Builder withMetadata(DecisionMetadata metadata) {
            this.metadata = metadata;
            return this;
        }

        public InMemoryDecisionEngine build() {
            return new InMemoryDecisionEngine(this);
        }
    }
}
