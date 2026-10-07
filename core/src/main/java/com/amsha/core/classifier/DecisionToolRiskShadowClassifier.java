package com.amsha.core.classifier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import com.amsha.classifier.api.CandidateReductionPolicy;
import com.amsha.classifier.api.Choice;
import com.amsha.classifier.api.ClassifierResult;
import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;
import com.amsha.classifier.api.DecisionDefinition;
import com.amsha.classifier.api.DecisionEngine;
import com.amsha.classifier.api.DecisionPrimitive;
import com.amsha.classifier.api.DecisionRequest;
import com.amsha.classifier.api.DecisionResult;
import com.amsha.classifier.api.FailMode;
import com.amsha.classifier.api.InProcessDecisionEngine;
import com.amsha.classifier.api.ProviderCategory;
import com.amsha.classifier.api.ProviderPlacementValidator;
import com.amsha.classifier.api.QuestionKey;
import com.amsha.classifier.api.RedactionPolicy;
import com.amsha.classifier.api.RetentionPolicy;
import com.amsha.classifier.api.ReviewBand;
import com.amsha.classifier.api.ToolRiskPrediction;
import com.amsha.classifier.api.ToolRiskShadowClassifier;
import com.amsha.classifier.api.ToolRiskShadowContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;

/**
 * Optional tool-risk shadow classifier backed by a {@link DecisionEngine}.
 * Evaluates provisional allows observationally without changing the guard decision.
 */
@ApplicationScoped
@Unremovable
public class DecisionToolRiskShadowClassifier implements ToolRiskShadowClassifier {

    public static final String DECISION_ID = "tool.risk.shadow";
    public static final int SCHEMA_VERSION = 1;
    public static final QuestionKey<Choice> TOOL_RISK_KEY = QuestionKey.choice("tool_risk");
    public static final List<String> RISK_OPTIONS = List.of("LOW_RISK", "REQUIRE_APPROVAL", "BLOCK");

    /**
     * The question criteria the provider receives for the tool-risk decision.
     *
     * <p>It is a constant, and the same one is used by {@link #classify} and by {@link #definition}, so the
     * decision the shadow path actually asks and the decision the evaluation path registers cannot drift
     * apart. If they did, an M1 measurement over the registered definition would describe a question the
     * shadow path never asked.
     */
    public static final String TOOL_RISK_CRITERIA = "Evaluate the execution risk of this provisional tool invocation";

    private final boolean enabled;
    private final Instance<DecisionEngine> engineInstance;
    private final InProcessDecisionEngine directEngine;
    private final ObjectMapper mapper;

    @Inject
    public DecisionToolRiskShadowClassifier(
            Instance<DecisionEngine> engineInstance,
            Instance<ObjectMapper> mapperInstance,
            @ConfigProperty(name = "amsha.decision.shadow.tool-risk.enabled", defaultValue = "false") boolean enabled) {
        this.engineInstance = Objects.requireNonNull(engineInstance, "engineInstance");
        this.directEngine = null;
        this.mapper = mapperInstance != null && mapperInstance.isResolvable() ? mapperInstance.get() : null;
        this.enabled = enabled;
    }

    public DecisionToolRiskShadowClassifier(DecisionEngine engine, ObjectMapper mapper, boolean enabled) {
        this.engineInstance = null;
        this.directEngine = engine != null ? ProviderPlacementValidator.activateForClassifierSpi(engine) : null;
        this.mapper = mapper;
        this.enabled = enabled;
    }

    @Override
    public ClassifierResult<ToolRiskPrediction> classify(ToolRiskShadowContext context) {
        Objects.requireNonNull(context, "context");
        if (!enabled) {
            return new ClassifierResult.NoOpinion<>(NoOpinionReason.DISABLED);
        }
        try {
            InProcessDecisionEngine engine = DecisionShadowEngineResolver.resolve(directEngine, engineInstance);
            if (engine == null) {
                return new ClassifierResult.NoOpinion<>(NoOpinionReason.MISSING_MODEL);
            }
            String state = serializeContext(context);
            var reqBuilder = DecisionRequest.builder(DECISION_ID, SCHEMA_VERSION, state);
            QuestionKey<Choice> boundKey =
                    reqBuilder.registerChoice(TOOL_RISK_KEY.name(), TOOL_RISK_CRITERIA, RISK_OPTIONS);
            DecisionRequest request = reqBuilder.build();

            DecisionResult result = engine.evaluate(request);
            return DecisionShadowResultAdapter.adapt(
                    request, result, boundKey, RISK_OPTIONS, choice -> parsePrediction(choice.label()));
        } catch (RuntimeException | LinkageError _) {
            return new ClassifierResult.NoOpinion<>(NoOpinionReason.INFERENCE_ERROR);
        }
    }

    private String serializeContext(ToolRiskShadowContext context) {
        if (mapper != null) {
            try {
                return mapper.writeValueAsString(context);
            } catch (JsonProcessingException _) {
                // Fallback to deterministic string representation
            }
        }
        return "role=" + context.agentRole() + ",autonomy="
                + context.autonomyLevel().name()
                + ",tool=" + context.toolName() + ",goal=" + context.goal()
                + ",parameters=" + new TreeMap<>(context.parameters());
    }

    private static ToolRiskPrediction parsePrediction(String label) {
        if (label == null || !RISK_OPTIONS.contains(label)) {
            return null;
        }
        return switch (label) {
            case "LOW_RISK" -> ToolRiskPrediction.LOW_RISK;
            case "REQUIRE_APPROVAL" -> ToolRiskPrediction.REQUIRE_APPROVAL;
            case "BLOCK" -> ToolRiskPrediction.BLOCK;
            default -> null;
        };
    }

    /**
     * The registered definition for the tool-risk decision, so the evaluation path can evaluate the same
     * decision the shadow path asks — the instrument M1 (false-allow) is measured with.
     *
     * <p>It mirrors {@code IntentDecisionRouter.definition}: the id, schema version, primitive, question
     * vocabulary and criteria all come from this class's own constants, so registering the definition
     * invents no new decision vocabulary. The envelope matches the intent decision's — {@code CHOICE},
     * {@code RedactionPolicy.DEFAULT}, {@code EPHEMERAL_EXPORTABLE} (required because {@code REMOTE} is an
     * allowed provider), and {@code FAIL_OPEN} because the shadow path is observational and must never
     * tighten the guard decision it observes.
     *
     * @param reviewBand the confidence band the tool-risk policy resolves against
     * @param timeout the per-evaluation deadline
     * @return the tool-risk decision definition
     */
    public static DecisionDefinition definition(ReviewBand reviewBand, Duration timeout) {
        Objects.requireNonNull(reviewBand, "reviewBand");
        Objects.requireNonNull(timeout, "timeout");
        return new DecisionDefinition(
                DECISION_ID,
                SCHEMA_VERSION,
                DecisionPrimitive.CHOICE,
                "Evaluate the execution risk of a provisional tool invocation and escalate when warranted",
                "agentRole,autonomyLevel,toolName,parameters,goal",
                RedactionPolicy.DEFAULT,
                // EPHEMERAL_EXPORTABLE, not EPHEMERAL: this definition admits REMOTE in allowedProviders,
                // and DecisionDefinition rejects a local-only retention policy alongside REMOTE.
                RetentionPolicy.EPHEMERAL_EXPORTABLE,
                CandidateReductionPolicy.none(),
                reviewBand,
                Set.of(ProviderCategory.IN_PROCESS, ProviderCategory.SELF_HOSTED, ProviderCategory.REMOTE),
                FailMode.FAIL_OPEN,
                timeout,
                true);
    }
}
