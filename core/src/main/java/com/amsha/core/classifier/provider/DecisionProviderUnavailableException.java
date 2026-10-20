package com.amsha.core.classifier.provider;

/**
 * Raised when a configured decision provider cannot be turned into a usable engine.
 *
 * <p>This is the fail-closed signal of the provider wiring layer. It is deliberately a
 * {@link RuntimeException} rather than a checked one: every caller of the factory either already
 * handles a provider refusal (the evaluation path returns a payload-free refusal) or is a test, and a
 * checked exception would force {@code catch} blocks that swallow the reason.
 *
 * <p>The message names <em>operator configuration only</em> — a provider id and the rule it violates.
 * It never carries state, a resolved credential, a credential reference value, or a provider body, so
 * it is safe to log. The evaluation path is responsible for not echoing it into a user-visible
 * surface.
 */
public class DecisionProviderUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DecisionProviderUnavailableException(String message) {
        super(message);
    }

    public DecisionProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
