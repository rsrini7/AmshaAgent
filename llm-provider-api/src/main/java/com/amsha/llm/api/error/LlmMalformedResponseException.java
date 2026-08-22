package com.amsha.llm.api.error;

public final class LlmMalformedResponseException extends LlmProviderException {
    public LlmMalformedResponseException(String message) {
        super(message);
    }

    public LlmMalformedResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
