package com.arcclaw.core.agent.reasoning.error;

public final class LlmContextOverflowException extends LlmProviderException {
    public LlmContextOverflowException(String message) { super(message); }
    public LlmContextOverflowException(String message, Throwable cause) { super(message, cause); }
}
