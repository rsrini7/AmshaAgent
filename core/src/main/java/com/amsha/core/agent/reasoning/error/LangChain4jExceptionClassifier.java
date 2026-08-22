package com.amsha.core.agent.reasoning.error;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InvalidRequestException;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Locale;

import com.amsha.core.util.AgentErrorMessageUtil;
import com.amsha.llm.api.error.LlmAuthenticationException;
import com.amsha.llm.api.error.LlmExceptionClassifierChain;
import com.amsha.llm.api.error.LlmMalformedResponseException;
import com.amsha.llm.api.error.LlmProviderException;
import com.amsha.llm.api.error.LlmRateLimitException;
import com.amsha.llm.api.error.LlmServiceUnavailableException;
import com.amsha.llm.api.error.LlmToolCallException;

import io.quarkus.arc.Unremovable;

/**
 * Framework-level classifier for langchain4j transport exceptions. Stays in core:
 * it reuses {@link AgentErrorMessageUtil} and its langchain4j-core coupling is
 * framework-generic rather than provider-specific (Issue 03 decision).
 */
@ApplicationScoped
@Unremovable
public class LangChain4jExceptionClassifier extends LlmExceptionClassifierChain {

    public static final int ORDER = 100;

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
        return delegate(t);
    }

    private LlmProviderException classifyCause(Throwable cause, Throwable original) {
        if (cause instanceof InvalidRequestException
                || (cause instanceof HttpException http && http.statusCode() == 400)) {
            return classify400(cause, original);
        }
        if (cause instanceof HttpException http) {
            return classifyHttp(http.statusCode(), AgentErrorMessageUtil.safeMessage(cause), original);
        }
        return null;
    }

    private LlmProviderException classify400(Throwable cause, Throwable original) {
        String msg = AgentErrorMessageUtil.safeMessage(cause);
        // Tool-call errors first: a message like "tool call validation failed"
        // also matches isSchemaValidationError, so precedence matters.
        if (isToolCallError(msg)) {
            return new LlmToolCallException(msg, original);
        }
        if (isSchemaValidationError(msg)) {
            return new LlmMalformedResponseException(msg, original);
        }
        if (isAuthOrModelError(msg)) {
            String hint = (msg.contains("model_not_found") || msg.contains("does not exist"))
                    ? " MODEL MAY HAVE BEEN REMOVED — check provider's model list and update AMSHA_*_MODEL."
                    : "";
            return new LlmAuthenticationException(msg + hint, original);
        }
        if (isServiceDegraded(msg)) {
            return new LlmServiceUnavailableException(msg, original);
        }
        return null;
    }

    private LlmProviderException classifyHttp(int status, String msg, Throwable original) {
        // Check rate-limit message FIRST (any status), matching old behavior:
        // some providers return rate-limit-like errors on non-429 statuses
        if (isRateLimitError(msg)) {
            return new LlmRateLimitException(msg != null ? msg : "Rate limit hit", original);
        }
        return switch (status) {
            case 401, 403 -> new LlmAuthenticationException(msg != null ? msg : "Authentication failed", original);
            case 404 ->
                new LlmAuthenticationException(
                        "Model not found (404). MODEL MAY HAVE BEEN REMOVED — check provider's model list and update AMSHA_*_MODEL. "
                                + (msg != null ? msg : ""),
                        original);
            case 429 -> new LlmRateLimitException(msg != null ? msg : "Rate limit hit", original);
            default -> {
                if (status >= 500) {
                    yield new LlmServiceUnavailableException(msg != null ? msg : "Service unavailable", original);
                }
                yield null;
            }
        };
    }

    private static boolean isToolCallError(String msg) {
        return msg != null
                && (msg.contains("tool_use_failed")
                        || msg.contains("not in request.tools")
                        || msg.contains("tool call validation failed")
                        || msg.contains("json mode cannot be combined with tool/function calling"));
    }

    private static boolean isAuthOrModelError(String msg) {
        return msg != null
                && (msg.contains("model_not_found")
                        || msg.contains("does not exist")
                        || msg.contains("invalid_api_key")
                        || msg.contains("API_KEY_INVALID"));
    }

    private static boolean isServiceDegraded(String msg) {
        if (msg == null) return false;
        String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains("degraded") || lower.contains("cannot be invoked") || lower.contains("overloaded");
    }

    private static boolean isSchemaValidationError(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("tool call validation failed")
                || lower.contains("did not match schema")
                || lower.contains("value must be one of")
                || lower.contains("json schema")
                || lower.contains("invalid_tool");
    }
}
