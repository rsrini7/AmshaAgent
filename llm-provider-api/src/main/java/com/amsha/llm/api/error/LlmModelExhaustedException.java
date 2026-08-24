package com.amsha.llm.api.error;

/**
 * Thrown by the failover routing layer (core's ordered-failover chat model) when every profile in
 * the assigned chain is permanently disabled or in cooldown. Callers that already catch
 * {@link LlmProviderException} can handle this exception — it extends
 * {@link LlmServiceUnavailableException} so the existing retry/fallback logic applies.
 */
public final class LlmModelExhaustedException extends LlmServiceUnavailableException {

    public LlmModelExhaustedException(String message) {
        super(message);
    }
}
