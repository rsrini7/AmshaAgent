package com.arcclaw.core.llm;

public class LlmValidationException extends RuntimeException {
    public LlmValidationException(String msg) { super(msg); }
    public LlmValidationException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
