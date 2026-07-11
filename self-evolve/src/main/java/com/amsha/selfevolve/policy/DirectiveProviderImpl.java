package com.amsha.selfevolve.policy;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

import java.util.List;

import com.amsha.core.config.SelfEvolveConfig;
import com.amsha.core.memory.tiered.DirectiveProvider;
import com.amsha.selfevolve.api.DirectiveBundle;

/**
 * CDI implementation of {@link DirectiveProvider} backed by the self-evolve directive store.
 * <p>
 * This bean is {@link RequestScoped} and implements turn-level caching of directives
 * to avoid duplicate database queries across multiple iterations of the same request turn.
 * <p>
 * WARNING: This bean requires an active request context (e.g. active under `@ActivateRequestContext`
 * in `AgentLoop.runWithRole`). Do not inject this bean directly into threads/contexts that do not
 * have an active request context (such as Quartz scheduler threads).
 */
@RequestScoped
public class DirectiveProviderImpl implements DirectiveProvider {

    @Inject
    InteractionPolicySynthesizer synthesizer;

    @Inject
    SelfEvolveConfig config;

    private String cachedUserId;
    private DirectivesResult cachedResult;

    @Override
    public DirectivesResult getDirectives(String userId) {
        if (!config.enabled() || !config.directivesEnabled()) {
            return new DirectivesResult(List.of(), List.of());
        }

        if (cachedResult != null && userId != null && userId.equals(cachedUserId)) {
            return cachedResult;
        }

        DirectiveBundle bundle = synthesizer.buildDirectives(userId);

        List<DirectiveInfo> active = bundle.activeDirectives().stream()
                .map(d -> new DirectiveInfo(d.id(), d.text()))
                .toList();

        List<DirectiveInfo> shadow = bundle.shadowDirectives().stream()
                .map(d -> new DirectiveInfo(d.id(), d.text()))
                .toList();

        cachedUserId = userId;
        cachedResult = new DirectivesResult(active, shadow);
        return cachedResult;
    }
}
