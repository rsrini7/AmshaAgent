package com.amsha.core.llm;

import com.amsha.common.exception.AmshaException;

public class LlmValidationException extends AmshaException {
    public LlmValidationException(String msg) {
        super(msg);
    }

    public LlmValidationException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
