package com.amsha.core.memory.tiered;

import java.util.List;

/**
 * No-op implementation of {@link DirectiveProvider} returned when self-evolve is disabled.
 * Always returns empty lists — zero behavior change.
 * <p>
 * This is NOT a CDI bean — it's used programmatically by {@code ChatHistoryAssembler}
 * when the {@code Instance<DirectiveProvider>} is unsatisfied.
 */
public class NoOpDirectiveProvider implements DirectiveProvider {

    @Override
    public DirectivesResult getDirectives(String userId) {
        return new DirectivesResult(List.of(), List.of());
    }
}
