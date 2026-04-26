package com.arcclaw.core.agent.reasoning.error;

public abstract sealed class LlmProviderException extends RuntimeException
    permits LlmRateLimitException, LlmContextOverflowException, 
            LlmContentFilterException, LlmAuthenticationException, 
            LlmServiceUnavailableException, LlmMalformedResponseException, 
            LlmToolCallException {

    protected LlmProviderException(String message) {
        super(message);
    }

    protected LlmProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
