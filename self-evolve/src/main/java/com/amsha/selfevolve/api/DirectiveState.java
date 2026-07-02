package com.amsha.selfevolve.api;

/**
 * Lifecycle state for synthesized directives.
 * New directives start as CANDIDATE or SHADOW — never directly ACTIVE.
 *
 * <pre>
 * CANDIDATE → SHADOW → ACTIVE → DISABLED
 *                  ↘ DISABLED
 * </pre>
 */
public enum DirectiveState {
    /** Newly synthesized, not yet evaluated. */
    CANDIDATE,
    /** Injected with [EVAL] labeling, no behavior change. Evaluated for effectiveness. */
    SHADOW,
    /** Fully authoritative, standard injection into prompts. */
    ACTIVE,
    /** Admin-disabled or auto-faulted. No longer injected. */
    DISABLED
}
