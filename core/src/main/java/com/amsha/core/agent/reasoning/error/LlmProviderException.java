package com.amsha.core.agent.reasoning.error;

import com.amsha.core.exception.AmshaException;

public abstract sealed class LlmProviderException extends AmshaException
        permits LlmRateLimitException,
                LlmContextOverflowException,
                LlmContentFilterException,
                LlmAuthenticationException,
                LlmServiceUnavailableException,
                LlmMalformedResponseException,
                LlmToolCallException {

    protected LlmProviderException(String message) {
        super(message);
    }

    protected LlmProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
