package com.arcclaw.core.agent.reasoning.error;

public final class LlmAuthenticationException extends LlmProviderException {
    public LlmAuthenticationException(String message) { super(message); }
    public LlmAuthenticationException(String message, Throwable cause) { super(message, cause); }
}
