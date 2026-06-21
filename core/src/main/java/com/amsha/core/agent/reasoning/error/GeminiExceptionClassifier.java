package com.amsha.core.agent.reasoning.error;

public class GeminiExceptionClassifier extends LlmExceptionClassifierChain {

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
