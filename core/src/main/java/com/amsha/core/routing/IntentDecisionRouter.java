package com.amsha.core.routing;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import com.amsha.classifier.api.CandidateReductionPolicy;
import com.amsha.classifier.api.Choice;
import com.amsha.classifier.api.DecisionDefinition;
import com.amsha.classifier.api.DecisionEngine;
import com.amsha.classifier.api.DecisionPrimitive;
import com.amsha.classifier.api.DecisionRequest;
import com.amsha.classifier.api.DecisionResult;
import com.amsha.classifier.api.FailMode;
import com.amsha.classifier.api.IntentDecisionAction;
import com.amsha.classifier.api.IntentDecisionPolicy;
import com.amsha.classifier.api.IntentPolicyResolution;
import com.amsha.classifier.api.ProviderCategory;
import com.amsha.classifier.api.QuestionKey;
import com.amsha.classifier.api.RedactionPolicy;
import com.amsha.classifier.api.RetentionPolicy;
import com.amsha.classifier.api.ReviewBand;
import com.amsha.common.util.LogUtil;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Low-risk pilot router evaluating typed intent decisions (§2, §7.7).
 *
 * <p>Architecture invariants:
 * <ul>
 *   <li><b>Preserves fast paths:</b> Deterministic continuity commands and SPI triggers
 *       bypass this router completely.</li>
 *   <li><b>Rollback & Kill switch:</b> Disabled by default ({@code amsha.routing.intent-decision-router.enabled=false}).
 *       Disabling or absence of an engine restores existing routing immediately with zero migration.</li>
 *   <li><b>Three-band resolution:</b> Applies {@link IntentDecisionPolicy} producing
 *       {@link IntentDecisionAction#DIRECT_ROUTE}, {@link IntentDecisionAction#SECONDARY_REVIEW},
 *       or {@link IntentDecisionAction#DETERMINISTIC_FALLBACK}.</li>
 *   <li><b>Fail-safe:</b> Any provider timeout, overload, or exception falls back deterministically
 *       without interrupting user workflows.</li>
 * </ul>
 */
@ApplicationScoped
public class IntentDecisionRouter {

    private static final Logger log = Logger.getLogger(IntentDecisionRouter.class);

    public static final String DECISION_ID = "amsha.routing.intent.v1";
    public static final int SCHEMA_VERSION = 1;
    public static final String QUESTION_HANDLE = "intent_role_choice";

    private final Instance<DecisionEngine> decisionEngineInstance;
    private final boolean enabled;
    private final double reviewBandLow;
    private final double reviewBandHigh;
    private final long timeoutMs;
    private final boolean candidateEvidenceApproved;
    private final boolean costCeilingApproved;
    private final String rolloutOwner;

    @Inject
    public IntentDecisionRouter(
            Instance<DecisionEngine> decisionEngineInstance,
            @ConfigProperty(name = "amsha.routing.intent-decision-router.enabled", defaultValue = "false")
                    boolean enabled,
            @ConfigProperty(name = "amsha.routing.intent-decision-router.review-band.low", defaultValue = "0.50")
                    double reviewBandLow,
            @ConfigProperty(name = "amsha.routing.intent-decision-router.review-band.high", defaultValue = "0.85")
                    double reviewBandHigh,
            @ConfigProperty(name = "amsha.routing.intent-decision-router.timeout-ms", defaultValue = "500")
                    long timeoutMs,
            @ConfigProperty(
                            name = "amsha.routing.intent-decision-router.activation-gate.candidate-evidence-approved",
                            defaultValue = "false")
                    boolean candidateEvidenceApproved,
            @ConfigProperty(
                            name = "amsha.routing.intent-decision-router.activation-gate.cost-ceiling-approved",
                            defaultValue = "false")
                    boolean costCeilingApproved,
            @ConfigProperty(name = "amsha.routing.intent-decision-router.activation-gate.rollout-owner")
                    Optional<String> rolloutOwnerOpt) {
        this.decisionEngineInstance = decisionEngineInstance;
        this.enabled = enabled;
        this.reviewBandLow = reviewBandLow;
        this.reviewBandHigh = reviewBandHigh;
        this.timeoutMs = timeoutMs;
        this.candidateEvidenceApproved = candidateEvidenceApproved;
        this.costCeilingApproved = costCeilingApproved;
        this.rolloutOwner = rolloutOwnerOpt != null && rolloutOwnerOpt.isPresent() ? rolloutOwnerOpt.get() : "";
    }

    public IntentDecisionRouter(
            Instance<DecisionEngine> decisionEngineInstance,
            boolean enabled,
            double reviewBandLow,
            double reviewBandHigh,
            long timeoutMs) {
        this(decisionEngineInstance, enabled, reviewBandLow, reviewBandHigh, timeoutMs, false, false, Optional.empty());
    }

    public IntentDecisionRouter(
            Instance<DecisionEngine> decisionEngineInstance,
            boolean enabled,
            double reviewBandLow,
            double reviewBandHigh,
            long timeoutMs,
            boolean candidateEvidenceApproved,
            boolean costCeilingApproved,
            String rolloutOwner) {
        this(
                decisionEngineInstance,
                enabled,
                reviewBandLow,
                reviewBandHigh,
                timeoutMs,
                candidateEvidenceApproved,
                costCeilingApproved,
                Optional.ofNullable(rolloutOwner));
    }

    /**
     * Whether the intent decision router is configured enabled and has an available decision engine.
     */
    public boolean isEnabled() {
        return enabled && decisionEngineInstance != null && !decisionEngineInstance.isUnsatisfied();
    }

    /**
     * Whether the pilot activation gate criteria (candidate model evidence, approved cost ceiling,
     * and accountable rollout owner) have all been formally satisfied and approved.
     * The pilot is deferred (§7.7) until all three criteria are demonstrated.
     */
    public boolean isActivationGateApproved() {
        return candidateEvidenceApproved && costCeilingApproved && rolloutOwner != null && !rolloutOwner.isBlank();
    }

    /**
     * Whether direct routing to specialist roles is permitted.
     * Enforces the activation gate: even when enabled, direct routing is strictly rejected
     * until the activation gate has been approved.
     */
    public boolean isDirectRoutingPermitted() {
        return isEnabled() && isActivationGateApproved();
    }

    /**
     * Evaluates ambiguous user intent against the registered intent decision definition.
     * Enforces activation gate and result identity verification before producing policy actions.
     *
     * @param userMessage sanitized user input text
     * @param validRoles declared admissible role labels
     * @return deterministic intent policy resolution
     */
    public IntentPolicyResolution evaluateIntent(String userMessage, Set<String> validRoles) {
        if (!isEnabled() || userMessage == null || userMessage.isBlank()) {
            return IntentPolicyResolution.fallback(DECISION_ID, SCHEMA_VERSION, OptionalDouble.empty(), "DISABLED");
        }

        // §Activation Gate: The pilot is deferred. Do not evaluate uncalibrated candidate models or permit
        // direct routing until candidate evidence, cost limits, and rollout ownership exist.
        if (!isActivationGateApproved()) {
            log.debugf(
                    "Intent decision routing bypassed; activation gate is not approved (pilot is Deferred: candidateEvidence=%b, costCeiling=%b, owner='%s')",
                    candidateEvidenceApproved, costCeilingApproved, rolloutOwner);
            return IntentPolicyResolution.fallback(
                    DECISION_ID, SCHEMA_VERSION, OptionalDouble.empty(), "ACTIVATION_GATE_NOT_SATISFIED");
        }

        try {
            DecisionEngine engine = decisionEngineInstance.get();
            DecisionRequest.Builder requestBuilder = DecisionRequest.builder(DECISION_ID, SCHEMA_VERSION, userMessage);
            QuestionKey<Choice> boundQuestionKey = requestBuilder.registerChoice(
                    QUESTION_HANDLE,
                    "Classify user intent into an eligible agent role",
                    List.copyOf(validRoles != null ? validRoles : Set.of()));

            DecisionRequest request =
                    requestBuilder.timeout(Duration.ofMillis(timeoutMs)).build();

            DecisionResult result = engine.evaluate(request);
            ReviewBand reviewBand = new ReviewBand(reviewBandLow, reviewBandHigh);

            // Pass expected DECISION_ID and SCHEMA_VERSION to enforce result identity verification
            IntentPolicyResolution resolution = IntentDecisionPolicy.resolve(
                    result, DECISION_ID, SCHEMA_VERSION, boundQuestionKey, validRoles, reviewBand);

            // Defense-in-depth: Suppress DIRECT_ROUTE if direct routing is not permitted
            if (resolution.action() == IntentDecisionAction.DIRECT_ROUTE && !isDirectRoutingPermitted()) {
                log.infof("IntentDecisionRouter: direct routing suppressed because activation gate is not approved");
                return IntentPolicyResolution.fallback(
                        DECISION_ID, SCHEMA_VERSION, resolution.confidence(), "ACTIVATION_GATE_NOT_SATISFIED");
            }
            return resolution;
        } catch (Exception e) {
            log.warnf(
                    "Intent decision routing failed; falling back to deterministic baseline: %s",
                    LogUtil.rootMessage(e));
            return IntentPolicyResolution.fallback(
                    DECISION_ID, SCHEMA_VERSION, OptionalDouble.empty(), "ENGINE_EXCEPTION");
        }
    }

    /**
     * Constructs the canonical versioned decision definition for intent routing.
     */
    public static DecisionDefinition definition(ReviewBand reviewBand, Duration timeout) {
        Objects.requireNonNull(reviewBand, "reviewBand");
        Objects.requireNonNull(timeout, "timeout");
        return new DecisionDefinition(
                DECISION_ID,
                SCHEMA_VERSION,
                DecisionPrimitive.CHOICE,
                "Classify ambiguous user intent into an eligible agent role with review band fallback",
                "user_message",
                RedactionPolicy.DEFAULT,
                RetentionPolicy.EPHEMERAL,
                CandidateReductionPolicy.none(),
                reviewBand,
                Set.of(ProviderCategory.IN_PROCESS, ProviderCategory.SELF_HOSTED, ProviderCategory.REMOTE),
                FailMode.FAIL_OPEN,
                timeout,
                true);
    }
}
