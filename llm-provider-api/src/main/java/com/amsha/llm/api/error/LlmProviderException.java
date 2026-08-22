package com.amsha.llm.api.error;

import com.amsha.common.exception.AmshaException;

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
