package com.amsha.classifier.api;

/**
 * In-process decision engine contract for synchronous, local, network-free implementations.
 * Only in-process engines are eligible to satisfy classifier SPIs (e.g. shadow classifiers,
 * post-policy tool risk, answer quality).
 */
public interface InProcessDecisionEngine extends DecisionEngine {

    /**
     * Identifies this engine as in-process.
     *
     * @return {@link ProviderCategory#IN_PROCESS}
     */
    @Override
    default ProviderCategory category() {
        return ProviderCategory.IN_PROCESS;
    }

    /**
     * Activates and validates this engine for placement behind a classifier SPI.
     *
     * @return validated in-process engine
     * @throws SecurityException if placement rules are violated
     */
    default InProcessDecisionEngine activateForClassifierSpi() {
        ProviderPlacementValidator.validateClassifierSpiPlacement(this);
        return this;
    }
}
