package com.amsha.core.agent.reasoning.error;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Locale;

import com.amsha.core.util.AgentErrorMessageUtil;
import com.amsha.llm.api.error.LlmAuthenticationException;
import com.amsha.llm.api.error.LlmExceptionClassifierChain;
import com.amsha.llm.api.error.LlmProviderException;
import com.amsha.llm.api.error.LlmRateLimitException;
import com.amsha.llm.api.error.LlmServiceUnavailableException;
import com.amsha.llm.api.error.LlmToolCallException;

import io.quarkus.arc.Unremovable;

/**
 * Terminal fallback classifier (runs last in the chain). Stays in core because it
 * reuses {@link AgentErrorMessageUtil}; not SDK-coupled (decision recorded in
 * Issue 03 Comments).
 */
@ApplicationScoped
@Unremovable
public class GenericExceptionClassifier extends LlmExceptionClassifierChain {

    public static final int ORDER = 1000;

    @Override
    public int order() {
        return ORDER;
    }

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
        if (isAuthError(msg)) return new LlmAuthenticationException(msg, original);

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

    private static boolean isAuthError(String msg) {
        if (msg == null) return false;
        String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains("missing required authentication credential")
                || lower.contains("unauthenticated")
                || lower.contains("unauthorized")
                || lower.contains("invalid api key")
                || lower.contains("incorrect api key")
                || lower.contains("http 401")
                || lower.contains("status 401")
                || lower.contains("status code: 401");
    }
}
