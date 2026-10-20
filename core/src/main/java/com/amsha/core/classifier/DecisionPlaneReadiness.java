package com.amsha.core.classifier;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.amsha.classifier.api.DecisionEngine;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.runtime.Startup;

/**
 * Reports the decision plane's deferred, engine-less state at startup.
 *
 * <p>The Jev decision plane ships deferred (see {@code ADR-jev-decision-plane}, status Deferred). No
 * {@link DecisionEngine} bean or producer exists anywhere in the application, so every consumer of
 * {@code Instance<DecisionEngine>} resolves nothing: the three shadow classifiers return
 * {@code NoOpinion(MISSING_MODEL)} and {@code IntentDecisionRouter.isEnabled()} is permanently false.
 *
 * <p>That posture is deliberate and fails closed, but it is also silent. The
 * {@code amsha.decision.shadow.*} and {@code amsha.routing.intent-decision-router.*} switches are
 * documented configuration surface, so an operator who enables one has no signal that it cannot do
 * anything. This bean exists solely to make that visible once, at startup. It never enables, wires,
 * produces, or reroutes anything, and it logs nothing under the default configuration.
 */
@Startup
@ApplicationScoped
public class DecisionPlaneReadiness {

    private static final Logger log = Logger.getLogger(DecisionPlaneReadiness.class);

    private final Instance<DecisionEngine> engineInstance;
    private final boolean shadowIntentEnabled;
    private final boolean shadowToolRiskEnabled;
    private final boolean shadowAnswerQualityEnabled;
    private final boolean intentRouterEnabled;

    @Inject
    public DecisionPlaneReadiness(
            Instance<DecisionEngine> engineInstance,
            @ConfigProperty(name = "amsha.decision.shadow.intent.enabled", defaultValue = "false")
                    boolean shadowIntentEnabled,
            @ConfigProperty(name = "amsha.decision.shadow.tool-risk.enabled", defaultValue = "false")
                    boolean shadowToolRiskEnabled,
            @ConfigProperty(name = "amsha.decision.shadow.answer-quality.enabled", defaultValue = "false")
                    boolean shadowAnswerQualityEnabled,
            @ConfigProperty(name = "amsha.routing.intent-decision-router.enabled", defaultValue = "false")
                    boolean intentRouterEnabled) {
        this.engineInstance = Objects.requireNonNull(engineInstance, "engineInstance");
        this.shadowIntentEnabled = shadowIntentEnabled;
        this.shadowToolRiskEnabled = shadowToolRiskEnabled;
        this.shadowAnswerQualityEnabled = shadowAnswerQualityEnabled;
        this.intentRouterEnabled = intentRouterEnabled;
    }

    @PostConstruct
    void reportDeferredState() {
        // isResolvable(), not isUnsatisfied(): this mirrors the check the shadow classifiers actually make
        // in DecisionShadowEngineResolver, so the warning fires exactly when the feature would be inert.
        deferredWarning(
                        shadowIntentEnabled,
                        shadowToolRiskEnabled,
                        shadowAnswerQualityEnabled,
                        intentRouterEnabled,
                        engineInstance.isResolvable())
                .ifPresent(log::warn);
    }

    /**
     * Builds the startup warning for a decision plane that is switched on but cannot evaluate.
     *
     * @param shadowIntentEnabled whether {@code amsha.decision.shadow.intent.enabled} is set
     * @param shadowToolRiskEnabled whether {@code amsha.decision.shadow.tool-risk.enabled} is set
     * @param shadowAnswerQualityEnabled whether {@code amsha.decision.shadow.answer-quality.enabled} is set
     * @param intentRouterEnabled whether {@code amsha.routing.intent-decision-router.enabled} is set
     * @param engineAvailable whether exactly one {@link DecisionEngine} is resolvable
     * @return the warning text, or empty when the configuration is consistent or nothing is enabled
     */
    static Optional<String> deferredWarning(
            boolean shadowIntentEnabled,
            boolean shadowToolRiskEnabled,
            boolean shadowAnswerQualityEnabled,
            boolean intentRouterEnabled,
            boolean engineAvailable) {
        List<String> enabled = new ArrayList<>(4);
        if (shadowIntentEnabled) {
            enabled.add("amsha.decision.shadow.intent.enabled");
        }
        if (shadowToolRiskEnabled) {
            enabled.add("amsha.decision.shadow.tool-risk.enabled");
        }
        if (shadowAnswerQualityEnabled) {
            enabled.add("amsha.decision.shadow.answer-quality.enabled");
        }
        if (intentRouterEnabled) {
            enabled.add("amsha.routing.intent-decision-router.enabled");
        }
        if (enabled.isEmpty() || engineAvailable) {
            return Optional.empty();
        }
        return Optional.of("Decision plane is enabled but inert: " + String.join(", ", enabled)
                + " set, but no uniquely resolvable DecisionEngine bean is available, so every decision evaluation fails closed to"
                + " NoOpinion(MISSING_MODEL) and intent routing falls back to the deterministic baseline."
                + " This is the expected Deferred state (ADR-jev-decision-plane); disable these switches, or"
                + " supply one uniquely resolvable DecisionEngine, before expecting any decision-plane behaviour.");
    }
}
