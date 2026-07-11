package com.amsha.selfevolve.policy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.amsha.core.config.SelfEvolveConfig;
import com.amsha.core.memory.tiered.DirectiveFeedbackPort;
import com.amsha.selfevolve.api.SynthesizedDirective;
import com.amsha.selfevolve.store.DirectiveStorePort;

import org.jboss.logging.Logger;

/**
 * Tracks shadow directive injection per session and records hit/miss feedback
 * once the session reaches a terminal outcome.
 */
@ApplicationScoped
public class DirectiveFeedbackService implements DirectiveFeedbackPort {

    private static final Logger log = Logger.getLogger(DirectiveFeedbackService.class);

    @Inject
    SelfEvolveConfig config;

    @Inject
    ShadowLifecycleManager lifecycleManager;

    @Inject
    DirectiveStorePort directiveStore;

    final ConcurrentHashMap<String, Set<String>> shadowDirectiveIdsBySession = new ConcurrentHashMap<>();

    @Override
    public void noteShadowDirectivesInjected(String sessionId, String userId, List<String> shadowDirectiveIds) {
        if (!isEnabled(sessionId, userId) || shadowDirectiveIds == null || shadowDirectiveIds.isEmpty()) {
            return;
        }
        shadowDirectiveIdsBySession.compute(sessionId, (_sid, existing) -> {
            Set<String> ids = existing != null ? new LinkedHashSet<>(existing) : new LinkedHashSet<>();
            ids.addAll(shadowDirectiveIds);
            return ids;
        });
        log.debugf(
                "[DirectiveFeedback] Registered %d shadow directives for session %s",
                shadowDirectiveIds.size(), sessionId);
    }

    @Override
    public void recordSessionOutcome(String sessionId, String userId, boolean success) {
        if (!isEnabled(sessionId, userId)) {
            return;
        }
        Set<String> directiveIds = shadowDirectiveIdsBySession.remove(sessionId);
        if (directiveIds == null || directiveIds.isEmpty()) {
            return;
        }
        List<String> applied = new ArrayList<>();
        for (String directiveId : directiveIds) {
            SynthesizedDirective directive = directiveStore.getById(directiveId);
            if (directive == null) {
                continue;
            }
            SynthesizedDirective updated =
                    success ? lifecycleManager.recordHit(directive) : lifecycleManager.recordMiss(directive);
            applied.add(updated.id());
        }
        if (!applied.isEmpty()) {
            log.infof(
                    "[DirectiveFeedback] Recorded %s feedback for %d shadow directives in session %s",
                    success ? "positive" : "negative", applied.size(), sessionId);
        }
    }

    private boolean isEnabled(String sessionId, String userId) {
        return sessionId != null
                && !sessionId.isBlank()
                && userId != null
                && !userId.isBlank()
                && config.enabled()
                && config.directivesEnabled();
    }
}
