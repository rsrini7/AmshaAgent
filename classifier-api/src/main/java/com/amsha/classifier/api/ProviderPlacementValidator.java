package com.amsha.classifier.api;

import java.util.Objects;

/**
 * Centralized runtime validator enforcing structural provider placement constraints.
 * Protects synchronous, network-free classifier SPIs from unauthorized remote or
 * self-hosted engine placement.
 */
public final class ProviderPlacementValidator {

    private ProviderPlacementValidator() {}

    /**
     * Structurally validates that the candidate classifier SPI implementation does not violate
     * provider placement constraints. Remote and self-hosted providers are strictly forbidden
     * from classifier SPI placement. If the candidate is a {@link DecisionEngine}, it must
     * implement {@link InProcessDecisionEngine} and declare {@link ProviderCategory#IN_PROCESS}.
     *
     * @param candidate candidate classifier SPI implementation
     * @throws SecurityException if candidate is null or placement rules are violated
     */
    public static void validateClassifierSpiPlacement(Object candidate) {
        if (candidate == null) {
            throw new SecurityException("Classifier SPI candidate cannot be null");
        }
        if (candidate instanceof RemoteDecisionProvider) {
            throw new SecurityException("Implementation " + candidate.getClass().getName()
                    + " implements RemoteDecisionProvider; strictly forbidden from classifier SPI placement");
        }
        if (candidate instanceof SelfHostedDecisionProvider) {
            throw new SecurityException("Implementation " + candidate.getClass().getName()
                    + " implements SelfHostedDecisionProvider; strictly forbidden from classifier SPI placement");
        }
        if (candidate instanceof DecisionEngine engine) {
            validateEnginePlacement(engine);
        }
    }

    /**
     * Structurally validates that the candidate engine is an approved in-process implementation
     * eligible for network-free classifier SPI placement.
     *
     * @param engine the candidate decision engine
     * @throws SecurityException if the engine is null, does not implement {@link InProcessDecisionEngine},
     *                           implements remote/self-hosted interfaces, or declares a non-in-process category
     */
    public static void validateClassifierSpiPlacement(DecisionEngine engine) {
        validateClassifierSpiPlacement((Object) engine);
    }

    private static void validateEnginePlacement(DecisionEngine engine) {
        if (!(engine instanceof InProcessDecisionEngine)) {
            throw new SecurityException("Engine " + engine.getClass().getName()
                    + " does not implement InProcessDecisionEngine; cannot be placed behind classifier SPIs");
        }
        ProviderCategory cat = engine.category();
        if (cat != ProviderCategory.IN_PROCESS || !cat.permitsClassifierSpi()) {
            throw new SecurityException("Engine " + engine.getClass().getName() + " declares category " + cat
                    + " which does not permit classifier SPI placement");
        }
    }

    /**
     * Activates an in-process decision engine for placement behind a classifier SPI,
     * validating all placement constraints before returning the activated engine.
     *
     * @param engine candidate decision engine
     * @return validated in-process engine
     * @throws SecurityException if placement rules are violated
     */
    public static InProcessDecisionEngine activateForClassifierSpi(DecisionEngine engine) {
        validateClassifierSpiPlacement(engine);
        return (InProcessDecisionEngine) engine;
    }

    /**
     * Validates that the engine matches the expected deployment category.
     *
     * @param actual actual provider category
     * @param expected expected provider category
     * @throws IllegalArgumentException if categories mismatch
     */
    public static void validateCategory(ProviderCategory actual, ProviderCategory expected) {
        Objects.requireNonNull(actual, "actual category");
        Objects.requireNonNull(expected, "expected category");
        if (actual != expected) {
            throw new IllegalArgumentException("Provider category mismatch: expected " + expected + ", got " + actual);
        }
    }
}
