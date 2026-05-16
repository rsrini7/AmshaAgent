package com.arcclaw.core.llm;

import com.arcclaw.core.exception.ArcClawException;

public class LlmValidationException extends ArcClawException {
    public LlmValidationException(String msg) { super(msg); }
    public LlmValidationException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
