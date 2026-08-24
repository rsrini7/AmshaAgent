package com.amsha.core.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.ToolOperation;

import io.quarkus.test.Mock;

/**
 * Provides the standard {@link AutonomyPolicy} for {@code amsha-core} Quarkus test contexts,
 * where the concrete {@code StandardAutonomyPolicy} bean resides in the sibling
 * {@code amsha-security} module (extract-core-subsystems S7). The mapping mirrors
 * {@code StandardAutonomyPolicy}; if that policy changes, update this fixture.
 */
@ApplicationScoped
public class TestAutonomyPolicyProducer {

    @Produces
    @Mock
    @ApplicationScoped
    public AutonomyPolicy autonomyPolicy() {
        return new AutonomyPolicy() {
            @Override
            public boolean isAllowed(AutonomyLevel level, ToolOperation operation) {
                return switch (level) {
                    case FULL -> true;
                    case SUPERVISED -> operation == ToolOperation.READ_FS;
                    case BACKGROUND ->
                        operation == ToolOperation.READ_FS
                                || operation == ToolOperation.NETWORK_OUTBOUND
                                || operation == ToolOperation.DELEGATE_AUTONOMOUS;
                    case READONLY -> operation == ToolOperation.READ_FS;
                };
            }
        };
    }
}
