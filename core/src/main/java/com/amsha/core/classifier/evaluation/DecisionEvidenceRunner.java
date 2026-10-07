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

import com.amsha.classifier.api.DecisionLatencyBudget;
import com.amsha.classifier.api.ToolRiskShadowContext;
import com.amsha.core.classifier.DecisionToolRiskShadowClassifier;
import com.amsha.core.config.DecisionProviderConfig;
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
 */
@ApplicationScoped
@Unremovable
public class DecisionEvidenceRunner {

    /** The intent valid-role universe, mirrored from corpus-v1's expanded seven-role shortlist. */
    static final List<String> DEFAULT_INTENT_ROLES =
            List.of("coder", "researcher", "architect", "synthesizer", "debugger", "optimizer", "sentinel");

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
     * @return the summary with all M1–M5 metrics
     * @throws DecisionEvaluationRefusedException when the corpus version is not registered
     */
    public DecisionEvidenceSummary run(int corpusVersion, String actorUserId) {
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
        List<DecisionEvidenceCaseResult> results = new ArrayList<>();
        for (DecisionEvidenceCase c : corpus.cases()) {
            results.add(evaluateCase(c, actorUserId));
        }
        return DecisionEvidenceSummary.summarize(
                corpusVersion, results, priceRegistry, providerConfig, DecisionLatencyBudget.ADR_8_3);
    }

    private DecisionEvidenceCaseResult evaluateCase(DecisionEvidenceCase c, String actorUserId) {
        String decisionId = decisionIdFor(c.task());
        if (decisionId == null) {
            return DecisionEvidenceCaseResult.skippedNoDecision(
                    c, "no registered decision for task '" + c.task() + "'");
        }
        String stateJson;
        try {
            stateJson = buildStateJson(c, decisionId);
        } catch (RuntimeException _) {
            return DecisionEvidenceCaseResult.transformError(c, decisionId, "evidence state transformation failed");
        }
        Map<String, List<String>> options = choiceOptionsFor(c);
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
        return s;
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

    private static Map<String, List<String>> choiceOptionsFor(DecisionEvidenceCase c) {
        if (!DecisionEvidenceCase.TASK_INTENT.equals(c.task())) {
            return Map.of();
        }
        List<String> roles = c.options() != null && !c.options().isEmpty() ? c.options() : DEFAULT_INTENT_ROLES;
        return Map.of(IntentDecisionRouter.QUESTION_HANDLE, roles);
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
