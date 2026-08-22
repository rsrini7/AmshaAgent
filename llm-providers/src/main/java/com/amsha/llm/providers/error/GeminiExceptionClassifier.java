package com.amsha.llm.providers.error;

import jakarta.enterprise.context.ApplicationScoped;

import com.amsha.llm.api.error.LlmContentFilterException;
import com.amsha.llm.api.error.LlmExceptionClassifierChain;
import com.amsha.llm.api.error.LlmProviderException;

import io.quarkus.arc.Unremovable;

@ApplicationScoped
@Unremovable
public class GeminiExceptionClassifier extends LlmExceptionClassifierChain {

    public static final int ORDER = 0;

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public LlmProviderException classify(Throwable t) {
        Throwable cause = t;
        while (cause != null) {
            if (cause instanceof NullPointerException) {
                String msg = cause.getMessage();
                if (msg != null && (msg.contains("parts") || msg.contains("Part"))) {
                    return new LlmContentFilterException(
                            "Gemini returned an empty response or was blocked by safety filters. "
                                    + "Try rephrasing your request or adjusting safety settings.",
                            t);
                }
            }
            cause = cause.getCause();
        }
        return delegate(t);
    }
}
