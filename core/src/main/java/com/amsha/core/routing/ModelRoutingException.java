package com.amsha.core.routing;

/**
 * Thrown when the {@link ModelRouter} cannot resolve a requested provider or model configuration.
 *
 * <p>This is a configuration/state error (unknown provider name, missing provider bean, or an
 * unroutable model), not a transient provider failure. Transient provider failures during chat
 * are handled by {@link OrderedFailoverChatModel} / {@link MultiModelChatModel} health logic.</p>
 */
public class ModelRoutingException extends RuntimeException {

    public ModelRoutingException(String message) {
        super(message);
    }

    public ModelRoutingException(String message, Throwable cause) {
        super(message, cause);
    }
}
