package com.amsha.core.classifier;

import jakarta.enterprise.inject.Instance;

import com.amsha.classifier.api.DecisionEngine;
import com.amsha.classifier.api.InProcessDecisionEngine;
import com.amsha.classifier.api.ProviderPlacementValidator;

/** Resolves and validates an in-process engine for an observational classifier. */
final class DecisionShadowEngineResolver {

    private DecisionShadowEngineResolver() {}

    static InProcessDecisionEngine resolve(
            InProcessDecisionEngine directEngine, Instance<DecisionEngine> engineInstance) {
        if (directEngine != null) {
            return ProviderPlacementValidator.activateForClassifierSpi(directEngine);
        }
        if (engineInstance == null || !engineInstance.isResolvable()) {
            return null;
        }
        return ProviderPlacementValidator.activateForClassifierSpi(engineInstance.get());
    }
}
