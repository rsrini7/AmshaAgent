package com.arcclaw.core.agent.reasoning.error;

import com.arcclaw.core.exception.ArcClawException;

public abstract sealed class LlmProviderException extends ArcClawException
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
