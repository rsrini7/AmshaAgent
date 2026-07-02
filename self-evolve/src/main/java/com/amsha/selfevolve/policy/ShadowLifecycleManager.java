package com.amsha.selfevolve.policy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

import com.amsha.selfevolve.api.DirectiveState;
import com.amsha.selfevolve.api.SynthesizedDirective;
import com.amsha.selfevolve.store.DirectiveStorePort;

import org.jboss.logging.Logger;

/**
 * Manages the directive lifecycle: CANDIDATE → SHADOW → ACTIVE → DISABLED.
 * Handles promotion, demotion, and auto-disable based on evaluation feedback.
 */
@ApplicationScoped
public class ShadowLifecycleManager {

    private static final Logger log = Logger.getLogger(ShadowLifecycleManager.class);

    /** Minimum evaluation hits before a SHADOW directive can be promoted to ACTIVE. */
    private static final int PROMOTION_MIN_HITS = 3;

    /** Maximum misses before a SHADOW directive is auto-disabled. */
    private static final int AUTO_DISABLE_MAX_MISSES = 5;

    @Inject
    DirectiveStorePort directiveStore;

    /**
     * Transitions a directive from CANDIDATE to SHADOW.
     * Called immediately after synthesis.
     */
    public SynthesizedDirective activateShadow(SynthesizedDirective directive) {
        if (directive.state() != DirectiveState.CANDIDATE) {
            log.warnf(
                    "[DirectiveLifecycle] Cannot activate shadow from state %s (id=%s)",
                    directive.state(), directive.id());
            return directive;
        }
        SynthesizedDirective updated = directive.withState(DirectiveState.SHADOW);
        directiveStore.update(updated);
        log.infof("[DirectiveLifecycle] Directive %s promoted CANDIDATE → SHADOW", updated.id());
        return updated;
    }

    /**
     * Transitions a SHADOW directive to ACTIVE if it has enough evaluation hits.
     */
    public SynthesizedDirective promoteToActive(SynthesizedDirective directive) {
        if (!directive.isReadyForPromotion(PROMOTION_MIN_HITS)) {
            log.debugf(
                    "[DirectiveLifecycle] Directive %s not ready for promotion (hits=%d, need=%d)",
                    directive.id(), directive.evaluationHits(), PROMOTION_MIN_HITS);
            return directive;
        }
        SynthesizedDirective updated = directive.withState(DirectiveState.ACTIVE);
        directiveStore.update(updated);
        log.infof(
                "[DirectiveLifecycle] Directive %s promoted SHADOW → ACTIVE (hits=%d)",
                updated.id(), updated.evaluationHits());
        return updated;
    }

    /**
     * Disables a directive (admin action or auto-fault).
     */
    public SynthesizedDirective disable(SynthesizedDirective directive) {
        SynthesizedDirective updated = directive.withState(DirectiveState.DISABLED);
        directiveStore.update(updated);
        log.infof("[DirectiveLifecycle] Directive %s disabled (was %s)", updated.id(), directive.state());
        return updated;
    }

    /**
     * Records a positive evaluation for a SHADOW directive.
     * If enough hits accumulated, promotes to ACTIVE.
     */
    public SynthesizedDirective recordHit(SynthesizedDirective directive) {
        if (directive.state() != DirectiveState.SHADOW) {
            return directive;
        }
        SynthesizedDirective updated = directive.withHit();
        directiveStore.update(updated);
        // Auto-promote if threshold reached
        return promoteToActive(updated);
    }

    /**
     * Records a negative evaluation for a SHADOW directive.
     * Auto-disables if too many misses.
     */
    public SynthesizedDirective recordMiss(SynthesizedDirective directive) {
        if (directive.state() != DirectiveState.SHADOW) {
            return directive;
        }
        SynthesizedDirective updated = directive.withMiss();
        if (updated.evaluationMisses() >= AUTO_DISABLE_MAX_MISSES) {
            log.warnf(
                    "[DirectiveLifecycle] Directive %s auto-disabled after %d misses",
                    updated.id(), updated.evaluationMisses());
            return disable(updated);
        }
        directiveStore.update(updated);
        return updated;
    }

    /**
     * Checks all SHADOW directives and promotes/disables as needed.
     * Called periodically (e.g., daily cron).
     */
    public int reconcileAll() {
        List<SynthesizedDirective> shadows = directiveStore.getByState(DirectiveState.SHADOW, 100);
        int actions = 0;
        for (SynthesizedDirective d : shadows) {
            if (d.isReadyForPromotion(PROMOTION_MIN_HITS)) {
                promoteToActive(d);
                actions++;
            } else if (d.evaluationMisses() >= AUTO_DISABLE_MAX_MISSES) {
                disable(d);
                actions++;
            }
        }
        if (actions > 0) {
            log.infof("[DirectiveLifecycle] Reconciled %d shadow directives", actions);
        }
        return actions;
    }
}
