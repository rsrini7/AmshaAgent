package com.amsha.classifier.api;

import java.time.Duration;
import java.util.Objects;

/**
 * Data retention and residency constraints for decision provider interactions.
 *
 * @param maxRetention maximum duration a provider is permitted to retain state/telemetry
 * @param allowTrainingUse whether provider training on submitted state is permitted (strictly false by default)
 * @param localOnly whether evaluation is constrained to local/in-process execution
 */
public record RetentionPolicy(Duration maxRetention, boolean allowTrainingUse, boolean localOnly) {

    public static final RetentionPolicy EPHEMERAL = new RetentionPolicy(Duration.ZERO, false, true);

    /**
     * Ephemeral retention with training use forbidden and <em>no</em> local-only constraint.
     *
     * <p>This is the shape a {@link DecisionDefinition} needs when its {@code allowedProviders} set
     * contains {@link ProviderCategory#REMOTE}: {@link #EPHEMERAL} declares evaluation to be constrained
     * to local execution, which contradicts permitting a remote provider.
     */
    public static final RetentionPolicy EPHEMERAL_EXPORTABLE = new RetentionPolicy(Duration.ZERO, false, false);

    public RetentionPolicy {
        Objects.requireNonNull(maxRetention, "maxRetention");
        if (maxRetention.isNegative()) {
            throw new IllegalArgumentException("maxRetention must not be negative, got " + maxRetention);
        }
    }
}
