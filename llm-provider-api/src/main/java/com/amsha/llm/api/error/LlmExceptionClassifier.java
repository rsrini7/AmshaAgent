package com.amsha.llm.api.error;

/**
 * SPI for provider-specific LLM exception classification. Implementations inspect a
 * raw {@link Throwable} (provider SDK exception or transport failure) and return a
 * normalized {@link LlmProviderException}, or {@code null} when they do not recognize it.
 *
 * <p>Implementations are discovered as CDI beans by core's classification service.
 * Ordering: lower {@link #order()} values run first; provider-specific classifiers should
 * use small values so framework-generic fallbacks see the throwable last.
 */
public interface LlmExceptionClassifier {

    /**
     * @return the normalized exception for this throwable, or {@code null} if unrecognized
     *         (the next classifier in the chain is consulted).
     */
    LlmProviderException classify(Throwable t);

    /** Chain position; lower runs earlier. Provider-specific impls should return &lt; 100. */
    default int order() {
        return 1000;
    }
}
