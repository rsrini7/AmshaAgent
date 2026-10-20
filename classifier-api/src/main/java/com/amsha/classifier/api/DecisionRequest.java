package com.amsha.classifier.api;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded request submitted to {@link DecisionEngine#evaluate(DecisionRequest)}.
 * Contains a registered decision ID, schema version, bounded state projection,
 * typed question handles, timeout, and data policies.
 *
 * @param decisionId unique stable decision identifier
 * @param schemaVersion schema version integer
 * @param state bounded state projection string
 * @param questions registered atomic questions indexed by handle name
 * @param timeout execution timeout
 * @param targetCategory intended provider placement category
 * @param redactionPolicy redaction and masking rules
 * @param retentionPolicy data residency constraints
 */
public record DecisionRequest(
        String decisionId,
        int schemaVersion,
        String state,
        Map<String, DecisionQuestion<?>> questions,
        Duration timeout,
        ProviderCategory targetCategory,
        RedactionPolicy redactionPolicy,
        RetentionPolicy retentionPolicy) {

    public DecisionRequest {
        Objects.requireNonNull(decisionId, "decisionId");
        if (decisionId.isBlank()) {
            throw new IllegalArgumentException("decisionId must not be blank");
        }
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be >= 1, got " + schemaVersion);
        }
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(questions, "questions");
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("DecisionRequest must contain at least one question");
        }
        Map<String, DecisionQuestion<?>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, DecisionQuestion<?>> entry : questions.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey(), "question handle name");
            DecisionQuestion<?> q = Objects.requireNonNull(entry.getValue(), "question for handle " + name);
            if (!name.equals(q.key().name())) {
                throw new IllegalArgumentException("Key name mismatch: handle '" + name + "' != question key '"
                        + q.key().name() + "'");
            }
            copy.put(name, q);
        }
        questions = Collections.unmodifiableMap(copy);

        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive, got " + timeout);
        }
        Objects.requireNonNull(targetCategory, "targetCategory");
        Objects.requireNonNull(redactionPolicy, "redactionPolicy");
        Objects.requireNonNull(retentionPolicy, "retentionPolicy");
    }

    public static Builder builder(String decisionId, int schemaVersion, String state) {
        return new Builder(decisionId, schemaVersion, state);
    }

    /**
     * Checks whether the given question handle is registered in this request.
     *
     * @param key question handle
     * @return true if registered
     */
    public boolean contains(QuestionKey<?> key) {
        if (key == null) {
            return false;
        }
        DecisionQuestion<?> q = questions.get(key.name());
        return q != null && q.key().equals(key);
    }

    /**
     * Retrieves the question specification for a registered handle.
     *
     * @param <A> answer type
     * @param key question handle
     * @return typed question specification
     * @throws IllegalArgumentException if the handle is unknown
     */
    @SuppressWarnings("unchecked")
    public <A extends DecisionAnswer> DecisionQuestion<A> question(QuestionKey<A> key) {
        Objects.requireNonNull(key, "key");
        DecisionQuestion<?> q = questions.get(key.name());
        if (q == null || !q.key().equals(key)) {
            throw new IllegalArgumentException(
                    "Unknown question handle '" + key.name() + "' for decision '" + decisionId + "'");
        }
        return (DecisionQuestion<A>) q;
    }

    /**
     * Retrieves the question specification by handle name.
     *
     * @param name question handle name
     * @return question specification
     * @throws IllegalArgumentException if the handle name is unknown
     */
    public DecisionQuestion<?> question(String name) {
        Objects.requireNonNull(name, "name");
        DecisionQuestion<?> q = questions.get(name);
        if (q == null) {
            throw new IllegalArgumentException(
                    "Unknown question handle name '" + name + "' for decision '" + decisionId + "'");
        }
        return q;
    }

    @Override
    public String toString() {
        return "DecisionRequest[decisionId=" + decisionId
                + ", schemaVersion=" + schemaVersion
                + ", questionCount=" + questions.size()
                + ", handles=" + questions.keySet()
                + ", timeout=" + timeout
                + ", targetCategory=" + targetCategory + "]";
    }

    /**
     * Builder for constructing validated {@link DecisionRequest} instances.
     */
    public static final class Builder {
        private final String decisionId;
        private final int schemaVersion;
        private final String state;
        private final Map<String, DecisionQuestion<?>> questions = new LinkedHashMap<>();
        private final Object owner = new Object();
        private Duration timeout = Duration.ofSeconds(5);
        private ProviderCategory targetCategory = ProviderCategory.IN_PROCESS;
        private RedactionPolicy redactionPolicy = RedactionPolicy.DEFAULT;
        private RetentionPolicy retentionPolicy = RetentionPolicy.EPHEMERAL;

        private Builder(String decisionId, int schemaVersion, String state) {
            this.decisionId = decisionId;
            this.schemaVersion = schemaVersion;
            this.state = state;
        }

        public <A extends DecisionAnswer> Builder addQuestion(DecisionQuestion<A> question) {
            register(question);
            return this;
        }

        public <A extends DecisionAnswer> QuestionKey<A> register(DecisionQuestion<A> question) {
            Objects.requireNonNull(question, "question");
            String name = question.key().name();
            if (questions.containsKey(name)) {
                throw new IllegalArgumentException("Duplicate question handle: '" + name + "'");
            }
            QuestionKey<A> boundKey = question.key().withOwner(owner);
            DecisionQuestion<A> boundQuestion =
                    new DecisionQuestion<>(boundKey, question.criteria(), question.options(), question.rubricLevels());
            questions.put(name, boundQuestion);
            return boundKey;
        }

        public QuestionKey<Choice> registerChoice(String name, String criteria, List<String> options) {
            return register(DecisionQuestion.choice(name, criteria, options));
        }

        public QuestionKey<Score> registerScore(String name, String criteria, List<String> rubricLevels) {
            return register(DecisionQuestion.score(name, criteria, rubricLevels));
        }

        public QuestionKey<Noul> registerNoul(String name, String criteria) {
            return register(DecisionQuestion.noul(name, criteria));
        }

        public QuestionKey<Choice> addChoice(String name, String criteria, List<String> options) {
            return registerChoice(name, criteria, options);
        }

        public QuestionKey<Score> addScore(String name, String criteria, List<String> rubricLevels) {
            return registerScore(name, criteria, rubricLevels);
        }

        public QuestionKey<Noul> addNoul(String name, String criteria) {
            return registerNoul(name, criteria);
        }

        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        public Builder targetCategory(ProviderCategory targetCategory) {
            this.targetCategory = Objects.requireNonNull(targetCategory, "targetCategory");
            return this;
        }

        public Builder redactionPolicy(RedactionPolicy redactionPolicy) {
            this.redactionPolicy = Objects.requireNonNull(redactionPolicy, "redactionPolicy");
            return this;
        }

        public Builder retentionPolicy(RetentionPolicy retentionPolicy) {
            this.retentionPolicy = Objects.requireNonNull(retentionPolicy, "retentionPolicy");
            return this;
        }

        public DecisionRequest build() {
            return new DecisionRequest(
                    decisionId,
                    schemaVersion,
                    state,
                    questions,
                    timeout,
                    targetCategory,
                    redactionPolicy,
                    retentionPolicy);
        }
    }
}
