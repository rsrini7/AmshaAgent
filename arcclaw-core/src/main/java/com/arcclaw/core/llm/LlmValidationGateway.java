package com.arcclaw.core.llm;

/**
 * SPI for validating LLM credentials during onboarding.
 * Implemented by LlmModelFactory in arcclaw-app.
 * arcclaw-onboarding depends only on this interface.
 */
public interface LlmValidationGateway {
    /**
     * Makes a test call to verify the given API key and provider work.
     * @return true if the model responds with a sensible reply
     * @throws LlmValidationException if connection/auth fails
     */
    boolean validate(String provider, String model, String apiKey) throws LlmValidationException;
}
