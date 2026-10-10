package com.amsha.core.classifier.evaluation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.amsha.classifier.api.IntentDecisionPolicy;
import com.amsha.classifier.api.ReviewBand;
import com.amsha.classifier.api.ToolRiskDecisionPolicy;
import com.amsha.core.classifier.DecisionToolRiskShadowClassifier;
import com.amsha.core.routing.IntentDecisionRouter;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The one place a {@link RegisteredDecision} may come from.
 *
 * <p>It is a <b>static, explicit map</b>: no classpath scanning, no reflection, no id&rarr;definition
 * inference. A request naming a decision id that is not in the map is refused, never defaulted — the
 * map is the allow-list, and adding an entry is a deliberate code change.
 *
 * <p><b>It seeds exactly two decisions, one per gate that reads evidence.</b> The first is the router's
 * own decision ({@code amsha.routing.intent.v1} — the definition {@link IntentDecisionRouter} already
 * builds), because the Phase 3 evidence exists to justify activating that router and the Quality gate
 * reads its routing error (M2) and abstention rate (M3). The second is the tool-risk decision
 * ({@code tool.risk.shadow} — the definition {@link DecisionToolRiskShadowClassifier} builds), because
 * M1 (false-allow, the headline "must be exactly 0" gate) is a tool-risk metric and cannot be measured
 * end to end until a tool-risk decision is evaluable. Neither entry invents vocabulary: each is the
 * definition its own production path already constructs, so the evaluation path stays inside the
 * companion ADR's authority.
 *
 * <p>The intent definition's review band and timeout are read from the router's own configuration so the
 * registered definition tracks the router instead of drifting from it. The values are clamped to the
 * documented defaults when they cannot form a valid band or a positive deadline: this bean exists
 * whenever the application runs, including with evaluation disabled, and an inert bean must not fail
 * construction from the router's configured review band and timeout; the registry remains inert until an evaluation request selects a registered definition.
 */
@ApplicationScoped
public class DecisionDefinitionRegistry {

    private static final double DEFAULT_REVIEW_BAND_LOW = 0.50;
    private static final double DEFAULT_REVIEW_BAND_HIGH = 0.85;
    private static final long DEFAULT_TIMEOUT_MS = 500L;

    private final Map<String, RegisteredDecision> byId;

    @Inject
    public DecisionDefinitionRegistry(
            @ConfigProperty(name = "amsha.routing.intent-decision-router.review-band.low", defaultValue = "0.50")
                    double reviewBandLow,
            @ConfigProperty(name = "amsha.routing.intent-decision-router.review-band.high", defaultValue = "0.85")
                    double reviewBandHigh,
            @ConfigProperty(name = "amsha.routing.intent-decision-router.timeout-ms", defaultValue = "500")
                    long timeoutMs) {
        this(List.of(intentDecision(reviewBandLow, reviewBandHigh, timeoutMs), toolRiskDecision(timeoutMs)));
    }

    /**
     * Builds a registry from an explicit decision list. Package-visible to the application; used by
     * tests that need a decision other than the seeded one.
     *
     * @param decisions the registered decisions; ids must be unique
     */
    public DecisionDefinitionRegistry(List<RegisteredDecision> decisions) {
        Objects.requireNonNull(decisions, "decisions");
        Map<String, RegisteredDecision> map = new LinkedHashMap<>();
        for (RegisteredDecision decision : decisions) {
            Objects.requireNonNull(decision, "decision");
            if (map.putIfAbsent(decision.decisionId(), decision) != null) {
                throw new IllegalArgumentException("duplicate registered decision id '" + decision.decisionId() + "'");
            }
        }
        this.byId = Map.copyOf(map);
    }

    /**
     * Resolves a registered decision.
     *
     * @param decisionId the requested decision id
     * @return the registered decision, or empty when the id is unknown
     */
    public Optional<RegisteredDecision> find(String decisionId) {
        if (decisionId == null || decisionId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(decisionId.trim()));
    }

    /** The registered decision ids, for diagnostics. */
    public Set<String> decisionIds() {
        return byId.keySet();
    }

    private static RegisteredDecision intentDecision(double low, double high, long timeoutMs) {
        ReviewBand reviewBand = validBand(low, high)
                ? new ReviewBand(low, high)
                : new ReviewBand(DEFAULT_REVIEW_BAND_LOW, DEFAULT_REVIEW_BAND_HIGH);
        return new RegisteredDecision(
                IntentDecisionRouter.definition(reviewBand, timeoutFor(timeoutMs)),
                List.of(QuestionSpec.callerSupplied(
                        IntentDecisionRouter.QUESTION_HANDLE,
                        IntentDecisionRouter.INTENT_CRITERIA,
                        List.of(IntentDecisionPolicy.NO_OPINION_LABEL))));
    }

    /**
     * The tool-risk decision — the instrument M1 (false-allow) is measured with.
     *
     * <p>M1 is a tool-risk metric and the ADR's headline "must be exactly 0" gate, so a tool-risk decision
     * must be evaluable for the evidence pipeline to report it end to end. Its vocabulary comes entirely
     * from {@link DecisionToolRiskShadowClassifier}, and its band from {@link ToolRiskDecisionPolicy}, so
     * the registered decision is the one the shadow path already asks rather than a second decision with
     * its own invented labels. Its deadline reuses the evaluation deadline: tool-risk has no separate
     * operational knob, and the value is a ceiling the caller may only tighten.
     */
    private static RegisteredDecision toolRiskDecision(long timeoutMs) {
        return new RegisteredDecision(
                DecisionToolRiskShadowClassifier.definition(
                        ToolRiskDecisionPolicy.DEFAULT_REVIEW_BAND, timeoutFor(timeoutMs)),
                List.of(QuestionSpec.fixedChoice(
                        DecisionToolRiskShadowClassifier.TOOL_RISK_KEY.name(),
                        DecisionToolRiskShadowClassifier.TOOL_RISK_CRITERIA,
                        DecisionToolRiskShadowClassifier.RISK_OPTIONS)));
    }

    private static Duration timeoutFor(long timeoutMs) {
        return Duration.ofMillis(timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS);
    }

    private static boolean validBand(double low, double high) {
        return Double.isFinite(low) && Double.isFinite(high) && low >= 0.0 && high <= 1.0 && low <= high;
    }
}
