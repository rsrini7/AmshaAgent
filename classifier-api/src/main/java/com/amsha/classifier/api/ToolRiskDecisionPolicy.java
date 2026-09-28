package com.amsha.classifier.api;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Deterministic policy module for tool-risk decision evaluation.
 *
 * <p>Enforces:
 * <ul>
 *   <li><b>Review bands:</b> Uses {@link ReviewBand} thresholds to route high confidence/probability
 *       to {@link ToolRiskAction#BLOCK}, medium confidence/probability to {@link ToolRiskAction#REQUIRE_APPROVAL}
 *       (human review), and low confidence to abstention/fallback.</li>
 *   <li><b>Deterministic fallback:</b> Clean {@link DecisionResult.NoOpinion} preserves the
 *       existing decision under {@link FailMode#FAIL_OPEN}, or escalates under
 *       {@link FailMode#FAIL_CLOSED}. Low-confidence restrictive labels escalate to
 *       {@link ToolRiskAction#REQUIRE_APPROVAL} even under fail-open; non-restrictive labels follow
 *       the configured fallback.</li>
 *   <li><b>Monotonic escalation:</b> Escalation is strictly monotonic:
 *       {@code BLOCK > REQUIRE_APPROVAL > existing decision (ALLOW)}. A decision can escalate risk,
 *       but can NEVER downgrade an existing block or bypass HITL approval.</li>
 *   <li><b>Bounded metadata:</b> Outcomes and telemetry are payload-free by construction.</li>
 * </ul>
 */
public final class ToolRiskDecisionPolicy {

    public static final ReviewBand DEFAULT_REVIEW_BAND = new ReviewBand(0.4, 0.8);

    private ToolRiskDecisionPolicy() {}

    /**
     * Resolves an effective tool-risk decision outcome by applying review bands, fallback,
     * and monotonic risk escalation against an existing cumulative policy resolution.
     *
     * @param existingResolution existing policy resolution (null for provisional allow)
     * @param result decision result from a decision engine
     * @param targetKey target choice question key (or null for first/composite choice)
     * @param reviewBand review band thresholds (defaults to {@link #DEFAULT_REVIEW_BAND} if null)
     * @param failMode failure posture on abstention/error (defaults to {@link FailMode#FAIL_OPEN} if null)
     * @return deterministic, payload-free policy resolution
     */
    public static PolicyResolution resolveCumulative(
            PolicyResolution existingResolution,
            DecisionResult result,
            QuestionKey<Choice> targetKey,
            ReviewBand reviewBand,
            FailMode failMode) {
        ReviewBand band = reviewBand != null ? reviewBand : DEFAULT_REVIEW_BAND;
        FailMode mode = failMode != null ? failMode : FailMode.FAIL_OPEN;

        if (result == null || result instanceof DecisionResult.NoOpinion) {
            DecisionResult.NoOpinion noOp = result instanceof DecisionResult.NoOpinion n
                    ? n
                    : new DecisionResult.NoOpinion(ClassifierResult.NoOpinionReason.INFERENCE_ERROR);
            OptionalDouble existingConfidence =
                    existingResolution != null ? existingResolution.confidence() : OptionalDouble.empty();
            return handleFallback(
                    existingResolution != null ? existingResolution.effectiveAction() : null,
                    null,
                    noOp.decisionId(),
                    noOp.schemaVersion(),
                    existingConfidence,
                    noOp.reason().name(),
                    mode);
        }

        DecisionResult.Opinion opinion = (DecisionResult.Opinion) result;
        ToolRiskAction existingAction = existingResolution != null ? existingResolution.effectiveAction() : null;
        SuggestedAction suggested = evaluateOpinion(opinion, targetKey, band, existingAction);
        if (suggested.fallback()) {
            OptionalDouble fallbackConf = suggested.confidence().isPresent()
                    ? suggested.confidence()
                    : (existingResolution != null ? existingResolution.confidence() : OptionalDouble.empty());
            return handleFallback(
                    existingAction,
                    suggested.action(),
                    opinion.decisionId(),
                    opinion.schemaVersion(),
                    fallbackConf,
                    suggested.reason(),
                    mode);
        }

        return applyMonotonicEscalation(
                existingResolution,
                suggested.action(),
                suggested.confidence(),
                opinion.decisionId(),
                opinion.schemaVersion(),
                suggested.reason());
    }

    /**
     * Resolves an effective tool-risk decision outcome by applying review bands, fallback,
     * and monotonic risk escalation.
     *
     * @param existingDecision existing decision (null for provisional allow, or REQUIRE_APPROVAL/BLOCK)
     * @param result decision result from a decision engine
     * @param targetKey target choice question key (or null for first/composite choice)
     * @param reviewBand review band thresholds (defaults to {@link #DEFAULT_REVIEW_BAND} if null)
     * @param failMode failure posture on abstention/error (defaults to {@link FailMode#FAIL_OPEN} if null)
     * @return deterministic, payload-free policy resolution
     */
    public static PolicyResolution resolve(
            ToolRiskAction existingDecision,
            DecisionResult result,
            QuestionKey<Choice> targetKey,
            ReviewBand reviewBand,
            FailMode failMode) {
        PolicyResolution existingRes = existingDecision != null
                ? new PolicyResolution(
                        "tool.risk.existing",
                        1,
                        existingDecision,
                        OptionalDouble.empty(),
                        false,
                        false,
                        "EXISTING_DECISION")
                : null;
        return resolveCumulative(existingRes, result, targetKey, reviewBand, failMode);
    }

    public static PolicyResolution resolve(
            ToolRiskAction existingDecision, DecisionResult result, ReviewBand reviewBand, FailMode failMode) {
        return resolve(existingDecision, result, null, reviewBand, failMode);
    }

    public static PolicyResolution resolve(
            ToolRiskAction existingDecision, DecisionResult result, ReviewBand reviewBand) {
        return resolve(existingDecision, result, null, reviewBand, FailMode.FAIL_OPEN);
    }

    public static PolicyResolution resolve(ToolRiskAction existingDecision, DecisionResult result) {
        return resolve(existingDecision, result, null, DEFAULT_REVIEW_BAND, FailMode.FAIL_OPEN);
    }

    public static PolicyResolution resolve(
            ToolRiskAction existingDecision, DecisionResult result, DecisionDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        return resolve(existingDecision, result, null, definition.reviewBand(), definition.failMode());
    }

    private static PolicyResolution handleFallback(
            ToolRiskAction existingDecision,
            ToolRiskAction suggestedAction,
            String decisionId,
            int schemaVersion,
            OptionalDouble confidence,
            String reason,
            FailMode failMode) {
        ToolRiskAction effective;
        boolean escalated = false;

        if (failMode == FailMode.FAIL_CLOSED) {
            if (existingDecision == null) {
                effective = ToolRiskAction.REQUIRE_APPROVAL;
                escalated = true;
            } else {
                effective = existingDecision;
            }
        } else {
            effective = existingDecision;
            if (effective == null && suggestedAction != null) {
                effective = ToolRiskAction.REQUIRE_APPROVAL;
                escalated = true;
            }
        }

        return new PolicyResolution(decisionId, schemaVersion, effective, confidence, escalated, true, reason);
    }

    private static SuggestedAction evaluateOpinion(
            DecisionResult.Opinion opinion,
            QuestionKey<Choice> targetKey,
            ReviewBand band,
            ToolRiskAction existingDecision) {
        if (opinion.answers().isEmpty()) {
            return new SuggestedAction(existingDecision, OptionalDouble.empty(), true, "EMPTY_ANSWERS");
        }

        if (targetKey != null) {
            DecisionAnswer ans = findTargetAnswer(opinion, targetKey);
            if (ans == null) {
                return new SuggestedAction(existingDecision, OptionalDouble.empty(), true, "MISSING_TARGET_KEY");
            }
            if (ans instanceof Choice choice) {
                return evaluateChoice(choice, band, opinion.ruleId());
            }
            return new SuggestedAction(existingDecision, OptionalDouble.empty(), true, "UNMAPPED_PRIMITIVE");
        }

        List<Choice> choiceAnswers = opinion.answers().values().stream()
                .filter(Choice.class::isInstance)
                .map(Choice.class::cast)
                .toList();

        if (choiceAnswers.isEmpty()) {
            return new SuggestedAction(existingDecision, OptionalDouble.empty(), true, "UNMAPPED_PRIMITIVE");
        }

        SuggestedAction strongest = null;
        for (Choice choice : choiceAnswers) {
            SuggestedAction candidate = evaluateChoice(choice, band, opinion.ruleId());
            if (candidate.fallback()) {
                return candidate;
            }
            if (strongest == null || severity(candidate.action()) > severity(strongest.action())) {
                strongest = candidate;
            }
            if (strongest.action() == ToolRiskAction.BLOCK) {
                break;
            }
        }
        return strongest != null
                ? strongest
                : new SuggestedAction(existingDecision, OptionalDouble.empty(), true, "EMPTY_ANSWERS");
    }

    private static DecisionAnswer findTargetAnswer(DecisionResult.Opinion opinion, QuestionKey<Choice> targetKey) {
        DecisionAnswer exact = opinion.answers().get(targetKey);
        if (exact != null) {
            return exact;
        }
        if (targetKey.owner() == null) {
            for (Map.Entry<QuestionKey<?>, DecisionAnswer> entry :
                    opinion.answers().entrySet()) {
                QuestionKey<?> key = entry.getKey();
                // Match on name *and* answer type, mirroring QuestionKey.equals. Name alone would let a
                // handle carrying the same name under a different primitive resolve here; today that is
                // caught downstream by the instanceof Choice check, but only by accident of ordering.
                if (key.name().equals(targetKey.name()) && key.answerType().equals(targetKey.answerType())) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private static SuggestedAction evaluateChoice(Choice choice, ReviewBand band, String ruleId) {
        String label = choice.label().toUpperCase(Locale.ROOT).trim();
        OptionalDouble confOpt = choice.confidence();
        String reason = ruleId != null && !ruleId.isBlank() ? ruleId : "CHOICE_ACTION";
        SuggestedAction labelSuggestion =
                switch (label) {
                    case "BLOCK", "DENY", "HIGH_RISK", "CRITICAL" ->
                        new SuggestedAction(ToolRiskAction.BLOCK, confOpt, false, reason);
                    case "REQUIRE_APPROVAL", "APPROVAL", "REVIEW", "PAUSE", "MEDIUM_RISK" ->
                        new SuggestedAction(ToolRiskAction.REQUIRE_APPROVAL, confOpt, false, reason);
                    case "ALLOW", "PERMIT", "LOW_RISK", "SAFE", "NONE", "ACCEPT", "PROCEED" ->
                        new SuggestedAction(null, confOpt, false, reason);
                    default -> new SuggestedAction(null, confOpt, true, "UNRECOGNIZED_CHOICE_LABEL");
                };

        if (confOpt.isPresent()) {
            double conf = confOpt.getAsDouble();
            if (band.isLow(conf)) {
                return new SuggestedAction(
                        labelSuggestion.action(),
                        confOpt,
                        true,
                        ClassifierResult.NoOpinionReason.LOW_CONFIDENCE.name());
            }
            if (band.isReview(conf)) {
                return new SuggestedAction(ToolRiskAction.REQUIRE_APPROVAL, confOpt, false, "REVIEW_BAND_ESCALATION");
            }
        }

        return labelSuggestion;
    }

    private static PolicyResolution applyMonotonicEscalation(
            PolicyResolution existing,
            ToolRiskAction suggested,
            OptionalDouble confidence,
            String decisionId,
            int schemaVersion,
            String reason) {
        int existingSeverity = existing != null ? severity(existing.effectiveAction()) : 0;
        int suggestedSeverity = severity(suggested);

        if (suggestedSeverity > existingSeverity) {
            return new PolicyResolution(decisionId, schemaVersion, suggested, confidence, true, false, reason);
        }

        return existing != null
                ? existing
                : new PolicyResolution(decisionId, schemaVersion, suggested, confidence, false, false, reason);
    }

    private static int severity(ToolRiskAction action) {
        if (action == null) return 0;
        return switch (action) {
            case REQUIRE_APPROVAL -> 1;
            case BLOCK -> 2;
        };
    }

    /**
     * Converts a policy resolution to a {@link ClassifierResult} for compatibility with existing
     * classifier interceptor consumers. Because the legacy opinion requires confidence, restrictive
     * resolutions without confidence cannot cross this compatibility seam.
     */
    public static ClassifierResult<ToolRiskAction> toClassifierResult(PolicyResolution resolution) {
        Objects.requireNonNull(resolution, "resolution");
        if (resolution.effectiveAction() != null) {
            if (resolution.confidence().isEmpty()) {
                throw new IllegalStateException(
                        "Cannot convert restrictive tool-risk resolution without confidence to ClassifierResult");
            }
            float conf = (float) resolution.confidence().getAsDouble();
            String rule = resolution.reason() != null && !resolution.reason().isBlank()
                    ? resolution.reason()
                    : "TOOL_RISK_POLICY";
            return new ClassifierResult.Opinion<>(resolution.effectiveAction(), conf, rule);
        }

        if (resolution.fallback()) {
            ClassifierResult.NoOpinionReason reason = ClassifierResult.NoOpinionReason.MISSING_MODEL;
            if (resolution.reason() != null) {
                try {
                    reason = ClassifierResult.NoOpinionReason.valueOf(resolution.reason());
                } catch (IllegalArgumentException _) {
                    // Keep default reason
                }
            }
            return new ClassifierResult.NoOpinion<>(reason);
        }

        return new ClassifierResult.NoOpinion<>(ClassifierResult.NoOpinionReason.DISABLED);
    }

    private record SuggestedAction(ToolRiskAction action, OptionalDouble confidence, boolean fallback, String reason) {}

    /**
     * Immutable, payload-free outcome metadata produced by policy evaluation.
     *
     * @param decisionId decision identifier
     * @param schemaVersion decision schema version
     * @param effectiveAction effective risk action, or null when the outcome is allow (no restriction)
     * @param confidence optional evaluated confidence or probability
     * @param escalated whether risk was increased above the existing decision
     * @param fallback whether fallback posture was applied due to NoOpinion or low confidence
     * @param reason bounded policy reason or rule identifier
     */
    public record PolicyResolution(
            String decisionId,
            int schemaVersion,
            ToolRiskAction effectiveAction,
            OptionalDouble confidence,
            boolean escalated,
            boolean fallback,
            String reason) {

        public PolicyResolution {
            Objects.requireNonNull(decisionId, "decisionId");
            Objects.requireNonNull(confidence, "confidence");
            Objects.requireNonNull(reason, "reason");
            // Escalation means risk was raised above the existing decision. A null effectiveAction means
            // "allow", which is the zero-severity floor and can never be an escalation, so the flag and the
            // action cannot disagree. Every construction site already satisfies this; enforcing it keeps a
            // future one from claiming an escalation the action does not show.
            if (escalated && effectiveAction == null) {
                throw new IllegalArgumentException("escalated=true requires a non-null effectiveAction");
            }
        }

        public PolicyResolution(
                String decisionId,
                int schemaVersion,
                ToolRiskAction effectiveAction,
                boolean escalated,
                boolean fallback,
                String reason) {
            this(decisionId, schemaVersion, effectiveAction, OptionalDouble.empty(), escalated, fallback, reason);
        }

        public boolean isAllow() {
            return effectiveAction == null;
        }

        public boolean isRequireApproval() {
            return effectiveAction == ToolRiskAction.REQUIRE_APPROVAL;
        }

        public boolean isBlock() {
            return effectiveAction == ToolRiskAction.BLOCK;
        }
    }
}
