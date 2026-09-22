package com.amsha.classifier.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Closed result contract for decision plane evaluations.
 * Sealed to either {@link Opinion} or {@link NoOpinion}.
 * Reuses {@link ClassifierResult.NoOpinionReason} for explicit absence and operational failures.
 */
public sealed interface DecisionResult permits DecisionResult.Opinion, DecisionResult.NoOpinion {

    String decisionId();

    int schemaVersion();

    String ruleId();

    default Optional<String> optionalRuleId() {
        return Optional.ofNullable(ruleId());
    }

    default boolean isOpinion() {
        return this instanceof Opinion;
    }

    default boolean isNoOpinion() {
        return this instanceof NoOpinion;
    }

    default Opinion asOpinion() {
        if (this instanceof Opinion op) {
            return op;
        }
        throw new IllegalStateException("DecisionResult is not an Opinion: " + this);
    }

    default NoOpinion asNoOpinion() {
        if (this instanceof NoOpinion noOp) {
            return noOp;
        }
        throw new IllegalStateException("DecisionResult is not a NoOpinion: " + this);
    }

    static OpinionBuilder opinionBuilder(String decisionId, int schemaVersion) {
        return new OpinionBuilder(decisionId, schemaVersion);
    }

    /**
     * Successful typed judgment containing answers correlated by exact {@link QuestionKey}
     * handles and payload-free execution telemetry.
     */
    record Opinion(
            String decisionId,
            int schemaVersion,
            Map<QuestionKey<?>, DecisionAnswer> answers,
            String ruleId,
            DecisionMetadata metadata)
            implements DecisionResult {

        public Opinion {
            Objects.requireNonNull(decisionId, "decisionId");
            if (decisionId.isBlank()) {
                throw new IllegalArgumentException("decisionId must not be blank");
            }
            if (schemaVersion < 1) {
                throw new IllegalArgumentException("schemaVersion must be >= 1, got " + schemaVersion);
            }
            Objects.requireNonNull(answers, "answers");
            if (answers.isEmpty()) {
                throw new IllegalArgumentException("Opinion must contain at least one answer");
            }
            Map<QuestionKey<?>, DecisionAnswer> copy = new LinkedHashMap<>();
            for (Map.Entry<QuestionKey<?>, DecisionAnswer> entry : answers.entrySet()) {
                QuestionKey<?> handle = Objects.requireNonNull(entry.getKey(), "answer question handle");
                DecisionAnswer ans = Objects.requireNonNull(entry.getValue(), "answer for handle " + handle);
                copy.put(handle, handle.cast(ans));
            }
            answers = Collections.unmodifiableMap(copy);

            if (ruleId != null && ruleId.isBlank()) {
                throw new IllegalArgumentException("ruleId must not be blank if present");
            }
            if (metadata == null) {
                metadata = DecisionMetadata.empty();
            }
        }

        public Opinion(String decisionId, int schemaVersion, Map<QuestionKey<?>, DecisionAnswer> answers) {
            this(decisionId, schemaVersion, answers, null, DecisionMetadata.empty());
        }

        public Opinion(
                String decisionId, int schemaVersion, Map<QuestionKey<?>, DecisionAnswer> answers, String ruleId) {
            this(decisionId, schemaVersion, answers, ruleId, DecisionMetadata.empty());
        }

        public <A extends DecisionAnswer> Opinion(
                String decisionId,
                int schemaVersion,
                QuestionKey<A> key,
                A answer,
                String ruleId,
                DecisionMetadata metadata) {
            this(decisionId, schemaVersion, Map.of(key, key.cast(answer)), ruleId, metadata);
        }

        /**
         * Identity-safe, compiler-checked lookup of an answer by exact typed handle.
         * Fails closed with {@link IllegalArgumentException} if the handle is unknown (including
         * owner token mismatches from foreign requests) or the answer is mistyped.
         *
         * @param <A> expected answer type
         * @param key registered question handle
         * @return typed answer
         * @throws IllegalArgumentException if the handle is unknown or type-mismatched
         */
        public <A extends DecisionAnswer> A get(QuestionKey<A> key) {
            Objects.requireNonNull(key, "key");
            DecisionAnswer raw = answers.get(key);
            if (raw == null) {
                throw new IllegalArgumentException(
                        "Unknown question handle '" + key + "' for decision '" + decisionId + "'");
            }
            return key.cast(raw);
        }

        /**
         * Synonym for {@link #get(QuestionKey)}.
         */
        public <A extends DecisionAnswer> A answer(QuestionKey<A> key) {
            return get(key);
        }

        /**
         * Returns the single answer when exactly one question was evaluated.
         *
         * @return single decision answer
         * @throws IllegalStateException if more than one answer exists
         */
        public DecisionAnswer singleAnswer() {
            if (answers.size() != 1) {
                throw new IllegalStateException("Expected exactly 1 answer, got " + answers.size());
            }
            return answers.values().iterator().next();
        }

        public static OpinionBuilder opinionBuilder(String decisionId, int schemaVersion) {
            return new OpinionBuilder(decisionId, schemaVersion);
        }
    }

    /**
     * Explicit absence or operational failure result.
     * Reuses {@link ClassifierResult.NoOpinionReason}. Diagnostic string representation
     * is strictly payload-free.
     */
    record NoOpinion(
            String decisionId, int schemaVersion, ClassifierResult.NoOpinionReason reason, String ruleId, String detail)
            implements DecisionResult {

        public NoOpinion {
            Objects.requireNonNull(decisionId, "decisionId");
            if (decisionId.isBlank()) {
                throw new IllegalArgumentException("decisionId must not be blank");
            }
            if (schemaVersion < 1) {
                throw new IllegalArgumentException("schemaVersion must be >= 1, got " + schemaVersion);
            }
            Objects.requireNonNull(reason, "reason");
            if (ruleId != null && ruleId.isBlank()) {
                throw new IllegalArgumentException("ruleId must not be blank if present");
            }
            detail = sanitizeDetail(detail);
        }

        public NoOpinion(ClassifierResult.NoOpinionReason reason) {
            this("unspecified", 1, reason, null, null);
        }

        public NoOpinion(String decisionId, int schemaVersion, ClassifierResult.NoOpinionReason reason) {
            this(decisionId, schemaVersion, reason, null, null);
        }

        public NoOpinion(String decisionId, int schemaVersion, ClassifierResult.NoOpinionReason reason, String ruleId) {
            this(decisionId, schemaVersion, reason, ruleId, null);
        }

        public static NoOpinion ofMalformedResponse(
                String decisionId, int schemaVersion, String ruleId, String diagnostic) {
            return new NoOpinion(
                    decisionId, schemaVersion, ClassifierResult.NoOpinionReason.INFERENCE_ERROR, ruleId, diagnostic);
        }

        public Optional<String> optionalDetail() {
            return Optional.ofNullable(detail);
        }

        private static final Pattern URL_CREDENTIALS =
                Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)[^\\s/@:]+:[^\\s/@]+@");
        private static final Pattern TELEGRAM_API_TOKEN =
                Pattern.compile("(?i)(https?://api\\.telegram\\.org/bot)[^/?#\\s]+");
        private static final Pattern TELEGRAM_TOKEN =
                Pattern.compile("(?<![A-Za-z0-9_-])\\d{6,}:[A-Za-z0-9_-]{20,}(?![A-Za-z0-9_-])");
        private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)(\\bbearer\\s+)[A-Za-z0-9._~+/=-]{8,}");
        private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
                "(?i)((?<![A-Za-z0-9_-])(?:[A-Za-z0-9]+[_-])*?(?:authorization|proxy-authorization|api[-_]?keys?|access[-_]?tokens?|auth[-_]?tokens?|bot[-_]?tokens?|client[-_]?secrets?|password|secret)(?:[_-][A-Za-z0-9]+)*\\b\\s*[=:]\\s*[\"']?)(?:bearer\\s+)?[^\\s,\"'&]+");
        private static final Pattern SK_API_KEY =
                Pattern.compile("(?<![A-Za-z0-9_-])sk-[A-Za-z0-9_-]{16,}(?![A-Za-z0-9_-])");
        private static final Pattern JWT_TOKEN = Pattern.compile(
                "(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]{8,}(?![A-Za-z0-9_-])");

        private static String sanitizeDetail(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String redacted = TELEGRAM_API_TOKEN.matcher(raw).replaceAll("$1[REDACTED]");
            redacted = URL_CREDENTIALS.matcher(redacted).replaceAll("$1[REDACTED]@");
            redacted = TELEGRAM_TOKEN.matcher(redacted).replaceAll("[REDACTED]");
            redacted = BEARER_TOKEN.matcher(redacted).replaceAll("$1[REDACTED]");
            redacted = SENSITIVE_ASSIGNMENT.matcher(redacted).replaceAll("$1[REDACTED]");
            redacted = SK_API_KEY.matcher(redacted).replaceAll("[REDACTED]");
            redacted = JWT_TOKEN.matcher(redacted).replaceAll("[REDACTED]");
            String clean = redacted.replaceAll("[\\r\\n\\t]", " ").trim();
            return clean.length() > 128 ? clean.substring(0, 128) + "..." : clean;
        }

        @Override
        public String toString() {
            return "DecisionResult.NoOpinion[decisionId=" + decisionId
                    + ", schemaVersion=" + schemaVersion
                    + ", reason=" + reason
                    + (ruleId != null ? ", ruleId=" + ruleId : "")
                    + ", hasDetail=" + (detail != null && !detail.isBlank()) + "]";
        }
    }

    /**
     * Builder for constructing validated {@link Opinion} instances with exact key correlation.
     */
    public static final class OpinionBuilder {
        private final String decisionId;
        private final int schemaVersion;
        private final Map<QuestionKey<?>, DecisionAnswer> answers = new LinkedHashMap<>();
        private String ruleId;
        private DecisionMetadata metadata = DecisionMetadata.empty();

        public OpinionBuilder(String decisionId, int schemaVersion) {
            this.decisionId = Objects.requireNonNull(decisionId, "decisionId");
            if (decisionId.isBlank()) {
                throw new IllegalArgumentException("decisionId must not be blank");
            }
            if (schemaVersion < 1) {
                throw new IllegalArgumentException("schemaVersion must be >= 1, got " + schemaVersion);
            }
            this.schemaVersion = schemaVersion;
        }

        public <A extends DecisionAnswer> OpinionBuilder put(QuestionKey<A> key, A answer) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(answer, "answer");
            for (QuestionKey<?> existing : answers.keySet()) {
                if (existing.name().equals(key.name())) {
                    throw new IllegalArgumentException("Duplicate answer for handle: '" + key.name() + "'");
                }
            }
            answers.put(key, key.cast(answer));
            return this;
        }

        public OpinionBuilder ruleId(String ruleId) {
            this.ruleId = ruleId;
            return this;
        }

        public OpinionBuilder metadata(DecisionMetadata metadata) {
            this.metadata = metadata;
            return this;
        }

        public Opinion build() {
            return new Opinion(decisionId, schemaVersion, answers, ruleId, metadata);
        }
    }

    /**
     * Compatibility bridge from legacy {@link ClassifierResult} to typed {@link DecisionResult}.
     *
     * @param <T> legacy value type
     * @param decisionId target decision identifier
     * @param schemaVersion target schema version
     * @param key target choice question key
     * @param legacyResult legacy classifier result
     * @return equivalent DecisionResult
     */
    static <T> DecisionResult fromClassifierResult(
            String decisionId, int schemaVersion, QuestionKey<Choice> key, ClassifierResult<T> legacyResult) {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(legacyResult, "legacyResult");

        if (legacyResult instanceof ClassifierResult.Opinion<T> op) {
            Choice choice = new Choice(op.value().toString(), op.confidence());
            return new Opinion(decisionId, schemaVersion, key, choice, op.ruleId(), DecisionMetadata.empty());
        } else if (legacyResult instanceof ClassifierResult.NoOpinion<T> noOp) {
            return new NoOpinion(decisionId, schemaVersion, noOp.reason());
        }
        throw new IllegalArgumentException("Unknown ClassifierResult subtype: " + legacyResult);
    }

    /**
     * Bridges this decision result to a legacy {@link ClassifierResult} using the provided choice key.
     * Preserves absence: if choice confidence is absent, returns {@code NoOpinion(LOW_CONFIDENCE)}
     * rather than fabricating 1.0f certainty. If ruleId is absent, returns {@code NoOpinion(INFERENCE_ERROR)}.
     *
     * @param choiceKey key of the choice question to extract
     * @return legacy ClassifierResult
     */
    default ClassifierResult<String> toClassifierResult(QuestionKey<Choice> choiceKey) {
        if (this instanceof Opinion op) {
            Choice choice = op.get(choiceKey);
            if (choice.confidence().isEmpty()) {
                return new ClassifierResult.NoOpinion<>(ClassifierResult.NoOpinionReason.LOW_CONFIDENCE);
            }
            String rule = op.ruleId();
            if (rule == null || rule.isBlank()) {
                return new ClassifierResult.NoOpinion<>(ClassifierResult.NoOpinionReason.INFERENCE_ERROR);
            }
            return new ClassifierResult.Opinion<>(
                    choice.label(), (float) choice.confidence().getAsDouble(), rule);
        } else if (this instanceof NoOpinion noOp) {
            return new ClassifierResult.NoOpinion<>(noOp.reason());
        }
        throw new IllegalStateException("Unknown DecisionResult type: " + this);
    }
}
