package com.amsha.core.classifier.evaluation;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One redacted case from a versioned decision-evidence corpus.
 *
 * <p>A case is the smallest unit of evidence: a single state to submit, the task it exercises, the
 * partition it stress-tests, and the label(s) a correct answer may carry. It deliberately carries no
 * policy — retention, redaction and the projection allow-list belong to the registered decision and
 * the provider's governance declaration, exactly as on the evaluation path, so a corpus cannot widen
 * what egresses.
 *
 * <p><b>Validation is in the compact constructor</b> so a case cannot exist in an unusable state: the
 * identifiers and the expected label must be present, and {@code task}/{@code partition} must be one of
 * the closed sets below. That last rule is what lets the runner {@code switch} on a known vocabulary
 * instead of silently skipping a case whose task it does not understand — an unknown task would
 * otherwise be counted as a pass.
 *
 * <p>The corpus is a shipped artifact, so {@link #toString()} is bounded and payload-free: it names the
 * identifiers and the partition, never the state.
 *
 * @param id stable case identifier, unique within its corpus
 * @param task the decision task exercised ({@code intent}, {@code tool_risk}, {@code answer_quality})
 * @param partition the stress partition the case belongs to
 * @param expectedLabel the label a correct decision must produce
 * @param acceptableLabels additional labels that also count as correct
 * @param riskCategory {@code SECURITY} marks a case whose unsafe allow is a false-allow
 * @param baselineAmbiguity for an intent case, whether the pinned deterministic baseline uniquely
 *     selects one eligible specialist ({@code AMBIGUOUS}/{@code UNAMBIGUOUS}); null for every other task
 * @param ambiguityRationale why the case carries that classification; required for every intent case
 * @param criteria optional extra instructions the case carries alongside its state
 * @param options optional closed option list the case declares
 * @param input the redacted state submitted to the provider
 * @param notes human-readable rationale for the case
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record DecisionEvidenceCase(
        String id,
        String task,
        String partition,
        String expectedLabel,
        List<String> acceptableLabels,
        String riskCategory,
        String baselineAmbiguity,
        String ambiguityRationale,
        String criteria,
        List<String> options,
        Map<String, Object> input,
        String notes) {

    /** The decision tasks a corpus case may exercise. */
    public static final String TASK_INTENT = "intent";

    /** Tool-risk task: false-allows here are the M1 gate. */
    public static final String TASK_TOOL_RISK = "tool_risk";

    /** Answer-quality task. */
    public static final String TASK_ANSWER_QUALITY = "answer_quality";

    /** Closed task vocabulary; a case naming anything else is refused. */
    public static final Set<String> TASKS = Set.of(TASK_INTENT, TASK_TOOL_RISK, TASK_ANSWER_QUALITY);

    /**
     * Baseline classification: the pinned deterministic baseline <b>cannot</b> uniquely select one
     * eligible specialist, so the case is in M2's denominator.
     */
    public static final String BASELINE_AMBIGUOUS = "AMBIGUOUS";

    /**
     * Baseline classification: the pinned deterministic baseline uniquely selects one eligible
     * specialist, so the case is <b>not</b> in M2's denominator.
     */
    public static final String BASELINE_UNAMBIGUOUS = "UNAMBIGUOUS";

    /** Closed baseline-ambiguity vocabulary. */
    public static final Set<String> BASELINE_AMBIGUITIES = Set.of(BASELINE_AMBIGUOUS, BASELINE_UNAMBIGUOUS);

    /**
     * Closed risk-category vocabulary; only {@code SECURITY} is in M1's denominator.
     *
     * <p>A case outside this set — including a misspelled or omitted category — is refused, because a
     * silent default would let a security case drop out of the M1 denominator and make the gate look
     * cleaner than it is.
     */
    public static final Set<String> RISK_CATEGORIES = Set.of("BENIGN", "SAFE", "SECURITY");

    /**
     * Closed partition vocabulary, mirroring the shadow corpus. Partitions exist so metrics can be
     * reported per stress category rather than as one blended number.
     */
    public static final Set<String> PARTITIONS = Set.of(
            "standard",
            "negation",
            "contradictory_criteria",
            "hostile_injected_state",
            "irrelevant_context",
            "malformed_partial_payload",
            "duplicate_identifiers",
            "shortlist_changes",
            "state_vs_criteria_placement");

    public DecisionEvidenceCase {
        id = requireText(id, "id");
        task = requireText(task, "task");
        partition = requireText(partition, "partition");
        expectedLabel = requireText(expectedLabel, "expectedLabel");
        riskCategory = requireText(riskCategory, "riskCategory");
        if (!RISK_CATEGORIES.contains(riskCategory)) {
            throw new IllegalArgumentException("unknown riskCategory '" + riskCategory + "' in evidence case " + id
                    + "; expected one of " + RISK_CATEGORIES);
        }
        if (!TASKS.contains(task)) {
            throw new IllegalArgumentException("unknown task '" + task + "' in evidence case " + id);
        }
        if (!PARTITIONS.contains(partition)) {
            throw new IllegalArgumentException("unknown partition '" + partition + "' in evidence case " + id);
        }
        // M2's denominator is the AMBIGUOUS subset, so the classification is a property of the corpus, not
        // of the candidate under test. Deriving it from a candidate's own policy band would let the
        // denominator move with the thing being measured, which makes the gate meaningless. Every intent
        // case must therefore be classified explicitly — an unclassified case would silently resize the
        // denominator — and no other task may carry the field, because routing is an intent concern.
        if (TASK_INTENT.equals(task)) {
            baselineAmbiguity =
                    requireText(baselineAmbiguity, "baselineAmbiguity").toUpperCase(Locale.ROOT);
            if (!BASELINE_AMBIGUITIES.contains(baselineAmbiguity)) {
                throw new IllegalArgumentException(
                        "unknown baselineAmbiguity '" + baselineAmbiguity + "' in evidence case " + id);
            }
            ambiguityRationale = requireText(ambiguityRationale, "ambiguityRationale");
        } else if (baselineAmbiguity != null || ambiguityRationale != null) {
            throw new IllegalArgumentException(
                    "only an intent case may declare baselineAmbiguity, but case " + id + " is task '" + task + "'");
        }
        acceptableLabels = acceptableLabels == null ? List.of() : List.copyOf(acceptableLabels);
        options = options == null ? List.of() : List.copyOf(options);
        input = input == null ? Map.of() : Map.copyOf(input);
    }

    /**
     * Whether an unsafe allow on this case is a false-allow.
     *
     * <p>Only security-relevant cases can produce one: a benign case answered {@code LOW_RISK} is a
     * correct answer, whereas a security case answered that way is the failure the M1 gate exists for.
     */
    public boolean isSecurityRelevant() {
        return "SECURITY".equalsIgnoreCase(riskCategory);
    }

    /** Whether abstaining is the expected outcome for this case. */
    public boolean isAbstentionExpected() {
        return "NO_OPINION".equalsIgnoreCase(expectedLabel);
    }

    /**
     * Whether the pinned deterministic baseline cannot uniquely select one eligible specialist here.
     *
     * <p>This is M2's denominator: routing error is measured only over the cases the baseline itself
     * cannot resolve. Only an intent case can be ambiguous — a non-intent case never declares the field,
     * so it is never in M2's denominator.
     *
     * <p><b>How a routing error is counted on this cohort.</b> For an {@link #BASELINE_AMBIGUOUS} case the
     * labelled correct answer is {@code NO_OPINION} (the baseline selects no specialist), so a decision that
     * routes to <em>any</em> concrete specialist is an M2 error. For a {@link #BASELINE_UNAMBIGUOUS} case the
     * correct specialist is named by {@code expectedLabel}, and routing to a different specialist is the error.
     * Either way the runner compares the resolved label to {@code expectedLabel}/{@code acceptableLabels}, so
     * the ambiguous cases are fully specified rather than an undefined subset.
     */
    public boolean isBaselineAmbiguous() {
        return BASELINE_AMBIGUOUS.equals(baselineAmbiguity);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("evidence case " + field + " must not be blank");
        }
        return trimmed;
    }

    @Override
    public String toString() {
        return "DecisionEvidenceCase[id=" + id + ", task=" + task + ", partition=" + partition + ", expected="
                + expectedLabel + ", securityRelevant=" + isSecurityRelevant() + "]";
    }

    /** Lower-cased task name for switch-free comparison. */
    public String normalizedTask() {
        return task.toLowerCase(Locale.ROOT);
    }
}
