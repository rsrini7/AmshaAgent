package com.amsha.core.agent.reasoning.error;

import java.util.Locale;

import com.amsha.core.util.AgentErrorMessageUtil;

public class GenericExceptionClassifier extends LlmExceptionClassifierChain {

    @Override
    public LlmProviderException classify(Throwable t) {
        Throwable cause = t;
        while (cause != null) {
            LlmProviderException result = classifyCause(cause, t);
            if (result != null) return result;
            cause = cause.getCause();
        }
        String cleanMsg = AgentErrorMessageUtil.sanitizeErrorMessage(AgentErrorMessageUtil.safeMessage(t));
        return new LlmServiceUnavailableException("Unclassified error: " + cleanMsg, t);
    }

    private LlmProviderException classifyCause(Throwable cause, Throwable original) {
        if (cause instanceof IllegalArgumentException) {
            String msg = cause.getMessage();
            if (msg != null && (msg.contains("Invalid REST Client URL") || msg.contains("chat/completions"))) {
                return new LlmServiceUnavailableException(
                        "Malformed LLM Base URL. " + "Check if the provider's base URL is correct. " + msg, original);
            }
        }

        String msg = AgentErrorMessageUtil.safeMessage(cause);
        if (isRateLimitError(msg)) return new LlmRateLimitException(msg, original);

        if (msg != null) {
            String lower = msg.toLowerCase(Locale.ROOT);
            if (lower.contains("timeout")
                    || lower.contains("timed out")
                    || (lower.contains("exceeded") && lower.contains("ms"))) {
                return new LlmServiceUnavailableException("LLM Request Timeout: " + msg, original);
            }
            // "not in request.tools" means the LLM called a non-existent tool (e.g., FINISH).
            // tool_use_failed / failed_generation means the model generated invalid tool params.
            // These are client-side issues, not provider failures — classify as LlmToolCallException
            // so the agent loop can extract failed_generation as the answer.
            if (lower.contains("not in request.tools")
                    || lower.contains("tool_use_failed")
                    || lower.contains("failed_generation")
                    || lower.contains("json mode cannot be combined with tool/function calling")) {
                return new LlmToolCallException(msg, original);
            }
        }
        return null;
    }
}
