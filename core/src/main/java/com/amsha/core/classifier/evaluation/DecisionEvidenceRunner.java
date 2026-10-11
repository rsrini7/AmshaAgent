package com.amsha.core.classifier.evaluation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.amsha.classifier.api.DecisionLatencyBudget;
import com.amsha.classifier.api.ToolRiskShadowContext;
import com.amsha.core.classifier.DecisionToolRiskShadowClassifier;
import com.amsha.core.config.DecisionProviderConfig;
import com.amsha.core.config.DecisionProviderSelector;
import com.amsha.core.routing.IntentDecisionRouter;
import com.amsha.security.api.AutonomyLevel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.arc.Unremovable;

/**
 * Orchestrates an evidence run over a registered corpus version and returns a payload-free summary.
 *
 * <p>It is the main-code counterpart to the test-only {@code DecisionShadowComparisonHarness}: it uses the
 * sanctioned {@link DecisionEvaluationService} egress path (never duplicating its audit or credential logic)
 * and reuses the same seeded definitions, so an evidence run measures the decisions the plane actually
 * registers. It is deliberately independent of the harness's {@code CandidateType} / metrics types.
 *
 * <p><b>One egress seam.</b> Production wiring supplies the service via {@link EvaluationFn}; the test seam
 * supplies a canned evaluator, so the request-shaping and metric maths are both unit-testable without a
 * provider. The runner never constructs an engine and never registers one, so the plane stays Deferred.
 *
 * <p><b>Every run is attributable.</b> A run mints its own identifier and resolves its provider
 * attribution from the same selection the evaluation path uses, so two responses are distinguishable and
 * a saved artifact names the provider and configured model that produced it. Opt-in diagnostics remain aggregate: disposition counts and provider/model/token coverage,
 * with no case-level data.
 *
 * <p><b>The evaluated scope belongs to the corpus.</b> The runner does not decide which tasks an evidence
 * run covers: it honours {@link DecisionEvidenceCorpus#evaluatedTasks()}, so a task outside that set (the
 * answer-quality cases in corpus v1) is an explicit, reported exclusion rather than a silent skip. A
 * corpus that declares a task this runner cannot evaluate is refused up front, because otherwise every one
 * of that task's cases would be skipped and its metric would read as a vacuous pass.
 */
@ApplicationScoped
@Unremovable
public class DecisionEvidenceRunner {

    private final EvaluationFn evaluator;
    private final DecisionEvidenceCorpusRegistry corpusRegistry;
    private final DecisionPriceScheduleRegistry priceRegistry;
    private final ObjectMapper mapper;
    private final DecisionProviderConfig providerConfig;

    @Inject
    public DecisionEvidenceRunner(
            DecisionEvaluationService evaluationService,
            DecisionEvidenceCorpusRegistry corpusRegistry,
            DecisionPriceScheduleRegistry priceRegistry,
            ObjectMapper mapper,
            DecisionProviderConfig providerConfig) {
        this(
                new EvaluationFn() {
                    @Override
                    public DecisionEvaluationOutcome evaluate(DecisionEvaluationRequest request, String actorUserId) {
                        return evaluationService.evaluate(request, actorUserId);
                    }

                    @Override
                    public boolean enabled() {
                        return evaluationService.isEnabled();
                    }
                },
                corpusRegistry,
                priceRegistry,
                mapper,
                providerConfig);
    }

    /** Test seam: supplies a canned evaluator with no real egress and no CDI. */
    DecisionEvidenceRunner(
            EvaluationFn evaluator,
            DecisionEvidenceCorpusRegistry corpusRegistry,
            DecisionPriceScheduleRegistry priceRegistry,
            ObjectMapper mapper,
            DecisionProviderConfig providerConfig) {
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.corpusRegistry = Objects.requireNonNull(corpusRegistry, "corpusRegistry");
        this.priceRegistry = Objects.requireNonNull(priceRegistry, "priceRegistry");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.providerConfig = Objects.requireNonNull(providerConfig, "providerConfig");
    }

    /**
     * Runs every case in the registered corpus version and returns the payload-free summary.
     *
     * @param corpusVersion the registered corpus version to evaluate
     * @param actorUserId the operator the audit events are attributed to
     * @return the summary with all M1–M5 metrics and its run attribution
     * @throws DecisionEvaluationRefusedException when the corpus version is not registered
     */
    public DecisionEvidenceSummary run(int corpusVersion, String actorUserId) {
        return run(corpusVersion, actorUserId, false);
    }

    /**
     * Runs every case in the registered corpus version and returns the payload-free summary.
     *
     * <p>Each invocation is its own run: it gets a fresh run identifier, and its measurements stand alone.
     * Two responses cannot be combined into one evidence package, because the M4 p95 is runner-measured
     * wall-clock and varies between runs — the attribution is what lets an operator tell them apart.
     *
     * @param corpusVersion the registered corpus version to evaluate
     * @param actorUserId the operator the audit events are attributed to
     * @param includeDiagnostics whether to include aggregate operational diagnostics; when false,
     *     the optional diagnostics field is absent
     * @return the summary with all M1–M5 metrics and its run attribution
     * @throws DecisionEvaluationRefusedException when the corpus version is not registered
     */
    public DecisionEvidenceSummary run(int corpusVersion, String actorUserId, boolean includeDiagnostics) {
        if (!evaluator.enabled()) {
            throw new DecisionEvaluationRefusedException(
                    DecisionRefusalReason.DISABLED,
                    "Decision evaluation is disabled (amsha.decision.evaluation.enabled=false); no egress is performed");
        }
        DecisionEvidenceCorpus corpus = corpusRegistry
                .find(corpusVersion)
                .orElseThrow(() -> new DecisionEvaluationRefusedException(
                        DecisionRefusalReason.UNREGISTERED_DECISION,
                        "No decision-evidence corpus is registered under version " + corpusVersion));
        // A corpus may only declare a task as evaluated if this runner can actually evaluate it. Without this
        // precondition, a corpus that declared a task with no registered decision would skip every one of that
        // task's cases, and the resulting metric — a zero denominator reported as complete — would read as a
        // pass. Refuse the whole run instead of measuring a scope we cannot cover.
        for (String task : corpus.evaluatedTasks()) {
            if (decisionIdFor(task) == null) {
                throw new DecisionEvaluationRefusedException(
                        DecisionRefusalReason.UNREGISTERED_DECISION,
                        "Corpus " + corpusVersion + " declares task '" + task
                                + "' as evaluated, but no decision is registered for that task");
            }
        }
        // This path sends only the registered question criteria; it has no channel for a case's own criteria
        // text (the request carries state and options, not policy). A corpus whose cases carry criteria was
        // therefore calibrated against a prompt this path does not send — the shadow comparison harness
        // appends that text to the goal/message, this runner does not — so its expected labels are not
        // achievable here and measuring M1/M2 on it would score the prompt difference, not the model.
        // Refuse the whole run rather than silently ignore the field: a partial run would be a scope change
        // nobody approved, the same reason the evaluated-scope check above refuses rather than skips.
        List<String> criteriaBearing = corpus.cases().stream()
                .filter(c -> c.criteria() != null && !c.criteria().isBlank())
                .map(DecisionEvidenceCase::id)
                .toList();
        if (!criteriaBearing.isEmpty()) {
            throw new DecisionEvaluationRefusedException(
                    DecisionRefusalReason.CORPUS_NOT_MEASURABLE,
                    "Corpus " + corpusVersion + " carries case-level criteria on " + criteriaBearing.size()
                            + " case(s) " + criteriaBearing + ", but the evidence path delivers only the registered"
                            + " question criteria. Those cases were calibrated against a prompt this path does not"
                            + " send, so their expected labels are not measurable here. Re-scope the corpus (v2)"
                            + " before running it.");
        }
        List<DecisionEvidenceCaseResult> results = new ArrayList<>();
        for (DecisionEvidenceCase c : corpus.cases()) {
            results.add(evaluateCase(c, actorUserId, corpus));
        }
        return DecisionEvidenceSummary.summarize(
                corpusVersion,
                attribution(newRunId()),
                results,
                priceRegistry,
                providerConfig,
                DecisionLatencyBudget.ADR_8_3,
                includeDiagnostics);
    }

    /** A fresh identifier for one run; two runs of the same corpus must be distinguishable. */
    private static String newRunId() {
        return UUID.randomUUID().toString();
    }

    /**
     * The provider attribution for a run, resolved from the same selection the evaluation path uses.
     *
     * <p>When the selection does not resolve, the configured provider id is still named so the artifact
     * records what was intended, with no lane and no model. A run whose cases were all refused is then
     * self-describing rather than anonymous — which is the difference between "the provider was missing"
     * and "the provider served this and failed the gates".
     */
    private DecisionEvidenceRunAttribution attribution(String runId) {
        DecisionProviderSelector.Selection selection = DecisionProviderSelector.select(providerConfig);
        if (selection instanceof DecisionProviderSelector.Selection.Selected selected) {
            return new DecisionEvidenceRunAttribution(
                    runId,
                    selected.providerId(),
                    selected.model(),
                    selected.lane().name());
        }
        return new DecisionEvidenceRunAttribution(
                runId, providerConfig.selected().orElse(null), null, null);
    }

    private DecisionEvidenceCaseResult evaluateCase(
            DecisionEvidenceCase c, String actorUserId, DecisionEvidenceCorpus corpus) {
        if (!corpus.evaluatesTask(c.task())) {
            return DecisionEvidenceCaseResult.skippedNoDecision(
                    c,
                    "task '" + c.task() + "' is outside corpus " + corpus.corpusVersion()
                            + "'s declared evaluatedTasks " + corpus.evaluatedTasks());
        }
        String decisionId = decisionIdFor(c.task());
        if (decisionId == null) {
            // Unreachable while run() refuses a corpus that declares an unevaluable task; kept as a fail-closed
            // guard so a future task vocabulary cannot quietly degrade to a skip here.
            return DecisionEvidenceCaseResult.skippedNoDecision(
                    c, "no registered decision for task '" + c.task() + "'");
        }
        String stateJson;
        Map<String, List<String>> options;
        try {
            stateJson = buildStateJson(c, decisionId);
            options = choiceOptionsFor(c);
        } catch (RuntimeException _) {
            return DecisionEvidenceCaseResult.transformError(c, decisionId, "evidence state transformation failed");
        }
        DecisionEvaluationRequest request =
                new DecisionEvaluationRequest(decisionId, stateJson, options, Optional.empty());
        try {
            long startedNanos = System.nanoTime();
            DecisionEvaluationOutcome outcome = evaluator.evaluate(request, actorUserId);
            long elapsedNanos = Math.max(1L, System.nanoTime() - startedNanos);
            return DecisionEvidenceCaseResult.fromOutcome(
                    c, decisionId, outcome, observedLabel(decisionId, outcome), elapsedNanos);
        } catch (DecisionEvaluationRefusedException ex) {
            return DecisionEvidenceCaseResult.refused(c, decisionId, ex.reason().name());
        }
    }

    private static String observedLabel(String decisionId, DecisionEvaluationOutcome outcome) {
        if (!outcome.opinion()) {
            return null;
        }
        String raw = outcome.answers().get(questionHandleFor(decisionId));
        return raw == null ? null : stripConfidence(raw);
    }

    private static String stripConfidence(String value) {
        int idx = value.indexOf(" (");
        return idx >= 0 ? value.substring(0, idx) : value;
    }

    private String buildStateJson(DecisionEvidenceCase c, String decisionId) {
        if (DecisionToolRiskShadowClassifier.DECISION_ID.equals(decisionId)) {
            return buildToolRiskState(c);
        }
        Object message = c.input().get("message");
        if (!(message instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("intent case '" + c.id() + "' has no usable message");
        }
        // The intent decision's state is a JSON object keyed by IntentDecisionRouter.STATE_KEY, not a bare
        // string: the projection redactor parses the state as a JSON map and fails closed on a scalar, and
        // a provider's egress allow-list names that key. A bare string would make every intent case abstain
        // against any projection-enforcing provider, silently zeroing the intent half of the evidence run.
        try {
            return mapper.writeValueAsString(Map.of(IntentDecisionRouter.STATE_KEY, s));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("could not serialize intent state for case '" + c.id() + "'", ex);
        }
    }

    private String buildToolRiskState(DecisionEvidenceCase c) {
        Map<String, Object> input = c.input();
        String agentRole = asText(input, "agent_role");
        String toolName = asText(input, "tool_name");
        AutonomyLevel autonomy = parseAutonomy(asText(input, "autonomy"));
        if (autonomy == null) {
            throw new IllegalArgumentException("tool-risk case '" + c.id()
                    + "' has no usable autonomy level (expected a value parseable to AutonomyLevel)");
        }
        Object rawParams = input.get("parameters");
        Map<String, Object> parameters = rawParams instanceof Map<?, ?> m ? castParameters(m) : Map.of();
        ToolRiskShadowContext context =
                new ToolRiskShadowContext(agentRole, autonomy, toolName, parameters, asTextOrEmpty(input, "goal"));
        try {
            return mapper.writeValueAsString(context);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("could not serialize tool-risk context for case '" + c.id() + "'", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castParameters(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private static String asText(Map<String, Object> input, String key) {
        Object v = input.get(key);
        return v instanceof String s ? s : null;
    }

    private static String asTextOrEmpty(Map<String, Object> input, String key) {
        String v = asText(input, key);
        return v == null ? "" : v;
    }

    /**
     * Tolerantly maps a corpus autonomy string to the engine's {@link AutonomyLevel}.
     *
     * <p>The corpus vocabulary is descriptive ({@code SEMI_AUTONOMOUS}, {@code UNKNOWN}) and does not
     * match the enum one-for-one; an unknown value is treated as absent so the context cannot be built and
     * the case fails closed (which is the correct outcome for an abstention-expected malformed case).
     */
    private static AutonomyLevel parseAutonomy(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "FULL" -> AutonomyLevel.FULL;
            case "SUPERVISED", "SEMI_AUTONOMOUS" -> AutonomyLevel.SUPERVISED;
            case "READONLY", "READ_ONLY" -> AutonomyLevel.READONLY;
            case "BACKGROUND" -> AutonomyLevel.BACKGROUND;
            default -> null;
        };
    }

    private static String decisionIdFor(String task) {
        if (DecisionEvidenceCase.TASK_INTENT.equals(task)) {
            return IntentDecisionRouter.DECISION_ID;
        }
        if (DecisionEvidenceCase.TASK_TOOL_RISK.equals(task)) {
            return DecisionToolRiskShadowClassifier.DECISION_ID;
        }
        return null;
    }

    private static String questionHandleFor(String decisionId) {
        if (IntentDecisionRouter.DECISION_ID.equals(decisionId)) {
            return IntentDecisionRouter.QUESTION_HANDLE;
        }
        return DecisionToolRiskShadowClassifier.TOOL_RISK_KEY.name();
    }

    /**
     * The option list an intent case is routed over, taken only from the case itself.
     *
     * <p>There is deliberately no default. A mirrored default would let a corpus revision change which roles
     * a case is judged against without touching the case, so the artifact under review and the harness that
     * measures it could diverge silently. The corpus validates the list at load, so an empty one is
     * unreachable for a registered corpus; the guard keeps a corpus that somehow slips through from being
     * evaluated against an implicit shortlist.
     */
    private static Map<String, List<String>> choiceOptionsFor(DecisionEvidenceCase c) {
        if (!DecisionEvidenceCase.TASK_INTENT.equals(c.task())) {
            return Map.of();
        }
        if (c.options() == null || c.options().isEmpty()) {
            throw new IllegalArgumentException("intent case '" + c.id()
                    + "' declares no options; the runner will not substitute a default shortlist");
        }
        return Map.of(IntentDecisionRouter.QUESTION_HANDLE, c.options());
    }

    /** The single egress seam the runner uses; production wiring supplies the evaluation service. */
    @FunctionalInterface
    interface EvaluationFn {
        DecisionEvaluationOutcome evaluate(DecisionEvaluationRequest request, String actorUserId)
                throws DecisionEvaluationRefusedException;

        default boolean enabled() {
            return true;
        }
    }
}
