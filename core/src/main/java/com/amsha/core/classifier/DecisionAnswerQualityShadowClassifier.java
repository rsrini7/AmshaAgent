package com.amsha.core.classifier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Objects;

import com.amsha.classifier.api.AnswerQualityPrediction;
import com.amsha.classifier.api.AnswerQualityShadowClassifier;
import com.amsha.classifier.api.AnswerQualityShadowContext;
import com.amsha.classifier.api.Choice;
import com.amsha.classifier.api.ClassifierResult;
import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;
import com.amsha.classifier.api.DecisionEngine;
import com.amsha.classifier.api.DecisionRequest;
import com.amsha.classifier.api.DecisionResult;
import com.amsha.classifier.api.InProcessDecisionEngine;
import com.amsha.classifier.api.ProviderPlacementValidator;
import com.amsha.classifier.api.QuestionKey;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;

/**
 * Optional answer-quality shadow classifier backed by a {@link DecisionEngine}.
 * Evaluates accepted candidate answers observationally without changing the retry budget.
 */
@ApplicationScoped
@Unremovable
public class DecisionAnswerQualityShadowClassifier implements AnswerQualityShadowClassifier {

    public static final String DECISION_ID = "answer.quality.shadow";
    public static final int SCHEMA_VERSION = 1;
    public static final QuestionKey<Choice> ANSWER_QUALITY_KEY = QuestionKey.choice("answer_quality");
    public static final List<String> QUALITY_OPTIONS = List.of("ACCEPTABLE", "RETRY_RECOMMENDED");

    private final boolean enabled;
    private final Instance<DecisionEngine> engineInstance;
    private final InProcessDecisionEngine directEngine;
    private final ObjectMapper mapper;

    @Inject
    public DecisionAnswerQualityShadowClassifier(
            Instance<DecisionEngine> engineInstance,
            Instance<ObjectMapper> mapperInstance,
            @ConfigProperty(name = "amsha.decision.shadow.answer-quality.enabled", defaultValue = "false")
                    boolean enabled) {
        this.engineInstance = Objects.requireNonNull(engineInstance, "engineInstance");
        this.directEngine = null;
        this.mapper = mapperInstance != null && mapperInstance.isResolvable() ? mapperInstance.get() : null;
        this.enabled = enabled;
    }

    public DecisionAnswerQualityShadowClassifier(DecisionEngine engine, ObjectMapper mapper, boolean enabled) {
        this.engineInstance = null;
        this.directEngine = engine != null ? ProviderPlacementValidator.activateForClassifierSpi(engine) : null;
        this.mapper = mapper;
        this.enabled = enabled;
    }

    @Override
    public ClassifierResult<AnswerQualityPrediction> classify(AnswerQualityShadowContext context) {
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
            QuestionKey<Choice> boundKey = reqBuilder.registerChoice(
                    ANSWER_QUALITY_KEY.name(),
                    "Evaluate whether the agent response satisfies the user goal",
                    QUALITY_OPTIONS);
            DecisionRequest request = reqBuilder.build();

            DecisionResult result = engine.evaluate(request);
            return DecisionShadowResultAdapter.adapt(
                    request, result, boundKey, QUALITY_OPTIONS, choice -> parsePrediction(choice.label()));
        } catch (RuntimeException | LinkageError _) {
            return new ClassifierResult.NoOpinion<>(NoOpinionReason.INFERENCE_ERROR);
        }
    }

    private String serializeContext(AnswerQualityShadowContext context) {
        if (mapper != null) {
            try {
                return mapper.writeValueAsString(context);
            } catch (JsonProcessingException _) {
                // Fallback to deterministic string representation
            }
        }
        return "role=" + context.agentRole() + ",goal=" + context.goal() + ",answer=" + context.answer()
                + ",toolInvokedInTurn=" + context.toolInvokedInTurn();
    }

    private static AnswerQualityPrediction parsePrediction(String label) {
        if (label == null || !QUALITY_OPTIONS.contains(label)) {
            return null;
        }
        return switch (label) {
            case "ACCEPTABLE" -> AnswerQualityPrediction.ACCEPTABLE;
            case "RETRY_RECOMMENDED" -> AnswerQualityPrediction.RETRY_RECOMMENDED;
            default -> null;
        };
    }
}
