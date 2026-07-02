package com.amsha.selfevolve.policy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

import com.amsha.core.config.SelfEvolveConfig;
import com.amsha.core.memory.tiered.DirectiveProvider;
import com.amsha.selfevolve.api.DirectiveBundle;
import com.amsha.selfevolve.api.SynthesizedDirective;

/**
 * CDI implementation of {@link DirectiveProvider} backed by the self-evolve directive store.
 * <p>
 * Consolidates both active and shadow directive retrieval into a single
 * {@code buildDirectives()} call to avoid double DB queries when both methods
 * are called in the same prompt assembly cycle.
 */
@ApplicationScoped
public class DirectiveProviderImpl implements DirectiveProvider {

    @Inject
    InteractionPolicySynthesizer synthesizer;

    @Inject
    SelfEvolveConfig config;

    /**
     * Returns active directive texts for authoritative prompt injection.
     */
    @Override
    public List<String> getInjectableDirectives(String userId) {
        if (!config.enabled() || !config.directivesEnabled()) {
            return List.of();
        }
        DirectiveBundle bundle = synthesizer.buildDirectives(userId);
        return bundle.activeDirectives().stream()
                .map(SynthesizedDirective::text)
                .toList();
    }

    /**
     * Returns shadow directive texts for evaluation-mode prompt injection.
     * <p>
     * Note: This makes a separate DB call from {@link #getInjectableDirectives(String)}.
     * If both are called in the same prompt assembly, consider using
     * {@link InteractionPolicySynthesizer#buildDirectives(String)} directly
     * and consuming the {@link DirectiveBundle} to avoid the duplicate query.
     */
    @Override
    public List<String> getShadowDirectives(String userId) {
        if (!config.enabled() || !config.directivesEnabled()) {
            return List.of();
        }
        DirectiveBundle bundle = synthesizer.buildDirectives(userId);
        return bundle.shadowDirectives().stream()
                .map(SynthesizedDirective::text)
                .toList();
    }
}
