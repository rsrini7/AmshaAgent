package com.amsha.core.classifier.evaluation;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One versioned, redacted decision-evidence corpus.
 *
 * <p>The corpus is a shipped artifact, not a request body: the evidence path must evaluate a fixed,
 * reproducible set so two runs of the same version are comparable. That is why the version is an
 * explicit part of the record rather than a filename convention alone — a caller selects a version and
 * gets exactly the cases registered under it.
 *
 * <p><b>Validation is the point of this type.</b> Everything the runner and the Quality gate depend on
 * is checked in the compact constructor, so a malformed or inadmissible corpus cannot be loaded at all:
 *
 * <ul>
 * <li><b>Provenance present.</b> A corpus needs a description and provenance so it can be reviewed;
 * the ADR's quality gate requires a reviewed artifact.</li>
 * <li><b>Redaction attested.</b> The declaration must be {@code APPROVED} and state that the corpus
 * contains neither real secrets nor real PII. A corpus that admits to carrying them is refused.</li>
 * <li><b>Structure is closed.</b> Cases are non-empty, bounded by {@link #MAX_CASES}, uniquely
 * identified, and each case validates its own task and partition (see {@link DecisionEvidenceCase}).</li>
 * <li><b>The baseline is pinned.</b> M2's denominator is the cases the deterministic baseline
 * cannot resolve, so the baseline must be part of the reviewed artifact rather than an unstated
 * assumption. A corpus without a named baseline version is refused.</li>
 * <li><b>The evaluated scope is declared.</b> Which tasks this corpus is <em>for</em> is part of the
 * artifact, not an assumption inside the runner: a case whose task is outside {@link #evaluatedTasks}
 * is an explicit, declared exclusion rather than an ad-hoc skip. Every declared task must carry at
 * least one case, because a metric over an empty set is reported as complete (0/0) and would read as
 * passing — a scope you cannot measure is not a scope you may declare.</li>
 * </ul>
 *
 * <p>The bounds are deliberately generous but finite: {@link #MAX_CASES} exists so a corpus cannot
 * turn one evidence run into an unbounded egress loop, and {@link #MAX_CASE_ID_LENGTH} keeps
 * identifiers from becoming a smuggling channel.
 *
 * @param corpusVersion the registered version this corpus is stored under; must be positive
 * @param description what the corpus is
 * @param provenance where the cases came from
 * @param baseline the deterministic baseline the intent cases are classified against
 * @param evaluatedTasks the tasks this corpus is evaluated for; a case outside this set is a declared
 *     exclusion, and every entry must be a known task that carries at least one case
 * @param redactionReview the attestation that the corpus carries no real secrets or PII
 * @param governance the corpus-level governance declaration, descriptive only
 * @param cases the validated cases
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record DecisionEvidenceCorpus(
        int corpusVersion,
        String description,
        String provenance,
        Baseline baseline,
        List<String> evaluatedTasks,
        RedactionReview redactionReview,
        Map<String, Object> governance,
        List<DecisionEvidenceCase> cases) {

    /** Upper bound on cases per corpus, so one evidence run cannot become unbounded egress. */
    public static final int MAX_CASES = 500;

    /** Upper bound on a case identifier, so an identifier cannot carry a payload. */
    public static final int MAX_CASE_ID_LENGTH = 128;

    public DecisionEvidenceCorpus {
        if (corpusVersion < 1) {
            throw new IllegalArgumentException("corpusVersion must be >= 1, got " + corpusVersion);
        }
        description = requireText(description, "description");
        provenance = requireText(provenance, "provenance");
        Objects.requireNonNull(baseline, "baseline");
        baseline.validate();
        // The evaluated scope is validated before the cases so a corpus that declares a task it cannot
        // measure is refused on the declaration, not on some incidental case defect.
        Objects.requireNonNull(evaluatedTasks, "evaluatedTasks");
        if (evaluatedTasks.isEmpty()) {
            throw new IllegalArgumentException("corpus " + corpusVersion + " declares no evaluatedTasks");
        }
        Set<String> declaredTasks = new LinkedHashSet<>();
        for (String task : evaluatedTasks) {
            Objects.requireNonNull(task, "evaluatedTasks entry");
            String normalized = task.trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(
                        "corpus " + corpusVersion + " declares a blank evaluatedTasks entry");
            }
            if (!DecisionEvidenceCase.TASKS.contains(normalized)) {
                throw new IllegalArgumentException("corpus " + corpusVersion + " declares unknown evaluated task '"
                        + normalized + "'; expected one of " + DecisionEvidenceCase.TASKS);
            }
            if (!declaredTasks.add(normalized)) {
                throw new IllegalArgumentException(
                        "corpus " + corpusVersion + " declares duplicate evaluated task '" + normalized + "'");
            }
        }
        Objects.requireNonNull(redactionReview, "redactionReview");
        redactionReview.validate();
        governance = governance == null ? Map.of() : Map.copyOf(governance);
        Objects.requireNonNull(cases, "cases");
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("corpus " + corpusVersion + " declares no cases");
        }
        if (cases.size() > MAX_CASES) {
            throw new IllegalArgumentException("corpus " + corpusVersion + " declares " + cases.size()
                    + " cases, above the " + MAX_CASES + "-case bound");
        }
        Set<String> ids = new HashSet<>();
        for (DecisionEvidenceCase evidenceCase : cases) {
            Objects.requireNonNull(evidenceCase, "case");
            if (evidenceCase.id().length() > MAX_CASE_ID_LENGTH) {
                throw new IllegalArgumentException("evidence case id exceeds " + MAX_CASE_ID_LENGTH + " characters");
            }
            if (!ids.add(evidenceCase.id())) {
                throw new IllegalArgumentException(
                        "duplicate evidence case id '" + evidenceCase.id() + "' in corpus " + corpusVersion);
            }
        }
        // A declared evaluated task with no case is a metric over an empty set: the runner reports it as
        // complete (0/0) and it reads as passing. Refuse the corpus rather than ship a gate that cannot fail.
        for (String task : declaredTasks) {
            boolean present = cases.stream().anyMatch(evidenceCase -> task.equals(evidenceCase.task()));
            if (!present) {
                throw new IllegalArgumentException("corpus " + corpusVersion + " declares task '" + task
                        + "' as evaluated but carries no case for it");
            }
        }
        evaluatedTasks = List.copyOf(declaredTasks);
        cases = List.copyOf(cases);
    }

    /** The number of cases in the corpus. */
    public int caseCount() {
        return cases.size();
    }

    /** The cases exercising one task, in corpus order. */
    public List<DecisionEvidenceCase> casesForTask(String task) {
        return cases.stream().filter(c -> c.task().equalsIgnoreCase(task)).toList();
    }

    /**
     * Whether {@code task} is in this corpus's declared evaluated scope.
     *
     * <p>The runner consults this instead of hard-coding which tasks it knows how to evaluate, so the
     * scope of an evidence run is a property of the reviewed corpus. A case outside the set is an
     * explicit exclusion the run reports, never a silent skip.
     */
    public boolean evaluatesTask(String task) {
        return task != null && evaluatedTasks.contains(task);
    }

    /** A corpus-level governance value, if declared. */
    public Optional<Object> governanceValue(String key) {
        return Optional.ofNullable(governance.get(key));
    }

    /**
     * The deterministic baseline the corpus's intent cases are classified against.
     *
     * <p>M2 asks whether the decision plane routes <em>better than the baseline it replaces</em>, so the
     * baseline must be named and versioned: a classification is only meaningful relative to a specific
     * baseline, and a later baseline revision would silently reclassify cases if the identity were
     * unstated. It is deliberately a declaration, not a code reference — the corpus is a shipped artifact
     * and must not depend on a class that may be renamed or removed.
     *
     * @param baselineId stable identifier of the baseline
     * @param baselineVersion positive revision of the baseline the classification was made against
     * @param description what the baseline does and how "unique eligible specialist" is judged
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Baseline(String baselineId, int baselineVersion, String description) {

        /**
         * Refuses a baseline declaration that cannot identify what the classification was made against.
         *
         * <p>Validation lives here rather than in a compact constructor for the same reason it does on
         * {@link RedactionReview}: the refusal must be observable as <em>the corpus</em> rejecting the
         * declaration, which is the seam a test can exercise.
         */
        public void validate() {
            requireText(baselineId, "baseline.baselineId");
            if (baselineVersion < 1) {
                throw new IllegalArgumentException("baseline.baselineVersion must be >= 1, got " + baselineVersion);
            }
            requireText(description, "baseline.description");
        }
    }

    /**
     * The attestation that a corpus is fit to evaluate.
     *
     * @param status the review status; only {@code APPROVED} is admissible
     * @param reviewedAt when the review happened
     * @param redactionVerifier what performed the redaction
     * @param containsRealSecrets whether real secrets remain — must be false
     * @param containsRealPii whether real PII remains — must be false
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RedactionReview(
            String status,
            String reviewedAt,
            String redactionVerifier,
            boolean containsRealSecrets,
            boolean containsRealPii) {

        /** The only admissible review status. */
        public static final String APPROVED = "APPROVED";

        /**
         * Refuses a corpus whose redaction is unattested or which admits to carrying real data.
         *
         * <p>Fails closed on every ambiguity: a missing status is not "probably fine", it is a refusal,
         * because the alternative is submitting unreviewed state to a provider.
         */
        public void validate() {
            if (status == null || !APPROVED.equalsIgnoreCase(status.trim())) {
                throw new IllegalArgumentException(
                        "corpus redaction review is not APPROVED (status=" + status + "); refusing to load it");
            }
            if (containsRealSecrets) {
                throw new IllegalArgumentException("corpus declares containsRealSecrets=true; refusing to load it");
            }
            if (containsRealPii) {
                throw new IllegalArgumentException("corpus declares containsRealPii=true; refusing to load it");
            }
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("corpus " + field + " must not be blank");
        }
        return trimmed;
    }

    @Override
    public String toString() {
        return "DecisionEvidenceCorpus[version=" + corpusVersion + ", cases=" + cases.size() + ", evaluatedTasks="
                + evaluatedTasks + ", governanceKeys=" + governance.size() + "]";
    }
}
