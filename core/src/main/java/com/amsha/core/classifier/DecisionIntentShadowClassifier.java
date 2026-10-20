package com.amsha.core.classifier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Objects;

import com.amsha.classifier.api.Choice;
import com.amsha.classifier.api.ClassifierResult;
import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;
import com.amsha.classifier.api.DecisionEngine;
import com.amsha.classifier.api.DecisionRequest;
import com.amsha.classifier.api.DecisionResult;
import com.amsha.classifier.api.InProcessDecisionEngine;
import com.amsha.classifier.api.IntentShadowClassifier;
import com.amsha.classifier.api.ProviderPlacementValidator;
import com.amsha.classifier.api.QuestionKey;
import com.amsha.core.agent.role.AgentRoleEnum;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;

/**
 * Optional intent shadow classifier backed by a {@link DecisionEngine}.
 * Evaluates user input observationally and never chooses the active route.
 */
@ApplicationScoped
@Unremovable
public class DecisionIntentShadowClassifier implements IntentShadowClassifier {

    public static final String DECISION_ID = "intent.route";
    public static final int SCHEMA_VERSION = 1;
    public static final QuestionKey<Choice> ROLE_KEY = QuestionKey.choice("role");

    private final boolean enabled;
    private final Instance<DecisionEngine> engineInstance;
    private final InProcessDecisionEngine directEngine;

    @Inject
    public DecisionIntentShadowClassifier(
            Instance<DecisionEngine> engineInstance,
            @ConfigProperty(name = "amsha.decision.shadow.intent.enabled", defaultValue = "false") boolean enabled) {
        this.engineInstance = Objects.requireNonNull(engineInstance, "engineInstance");
        this.directEngine = null;
        this.enabled = enabled;
    }

    public DecisionIntentShadowClassifier(DecisionEngine engine, boolean enabled) {
        this.engineInstance = null;
        this.directEngine = engine != null ? ProviderPlacementValidator.activateForClassifierSpi(engine) : null;
        this.enabled = enabled;
    }

    @Override
    public ClassifierResult<String> classify(String userMessage) {
        Objects.requireNonNull(userMessage, "userMessage");
        if (!enabled) {
            return new ClassifierResult.NoOpinion<>(NoOpinionReason.DISABLED);
        }
        try {
            InProcessDecisionEngine engine = DecisionShadowEngineResolver.resolve(directEngine, engineInstance);
            if (engine == null) {
                return new ClassifierResult.NoOpinion<>(NoOpinionReason.MISSING_MODEL);
            }
            List<String> allowedRoles = AgentRoleEnum.values().stream()
                    .map(AgentRoleEnum::toScopedName)
                    .toList();
            var reqBuilder = DecisionRequest.builder(DECISION_ID, SCHEMA_VERSION, userMessage);
            QuestionKey<Choice> boundKey = reqBuilder.registerChoice(
                    ROLE_KEY.name(), "Select the target agent role for this user request", allowedRoles);
            DecisionRequest request = reqBuilder.build();

            DecisionResult result = engine.evaluate(request);
            return DecisionShadowResultAdapter.adapt(request, result, boundKey, allowedRoles, choice -> {
                try {
                    return AgentRoleEnum.fromString(choice.label()).toScopedName();
                } catch (IllegalArgumentException _) {
                    return null;
                }
            });
        } catch (RuntimeException | LinkageError _) {
            return new ClassifierResult.NoOpinion<>(NoOpinionReason.INFERENCE_ERROR);
        }
    }
}
