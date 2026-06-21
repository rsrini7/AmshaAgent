package com.amsha.core.agent.reasoning.error;

import java.util.Locale;

public abstract class LlmExceptionClassifierChain {
    protected LlmExceptionClassifierChain next;

    public void setNext(LlmExceptionClassifierChain next) {
        this.next = next;
    }

    public abstract LlmProviderException classify(Throwable t);

    protected LlmProviderException delegate(Throwable t) {
        if (next != null) {
            return next.classify(t);
        }
        return null;
    }

    protected boolean isRateLimitError(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("ratelimitexceeded")
                || lower.contains("tokens per minute")
                || lower.contains("tokens per day")
                || lower.contains("tpm")
                || lower.contains("tpd")
                || lower.contains("rate limit")
                || lower.contains("rate_limit_exceeded")
                || lower.contains("too many requests")
                || lower.contains("no credits")
                || lower.contains("per_request_limit")
                || lower.contains("free tier limit")
                || lower.contains("x-ratelimit")
                || message.contains("429");
    }
}
