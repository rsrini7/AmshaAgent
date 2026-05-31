package com.arcclaw.core.agent.reasoning.error;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InvalidRequestException;

import com.arcclaw.core.util.AgentErrorMessageUtil;

public class LangChain4jExceptionClassifier extends LlmExceptionClassifierChain {

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
        if (isSchemaValidationError(msg)) {
            return new LlmMalformedResponseException(msg, original);
        }
        if (isToolCallError(msg)) {
            return new LlmToolCallException(msg, original);
        }
        if (isAuthOrModelError(msg)) {
            String hint = (msg.contains("model_not_found") || msg.contains("does not exist"))
                    ? " MODEL MAY HAVE BEEN REMOVED — check provider's model list and update ARCCLAW_*_MODEL."
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
                        "Model not found (404). MODEL MAY HAVE BEEN REMOVED — check provider's model list and update ARCCLAW_*_MODEL. "
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
        return msg != null && (msg.contains("tool_use_failed") || msg.contains("not in request.tools"));
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
        String lower = msg.toLowerCase();
        return lower.contains("degraded") || lower.contains("cannot be invoked") || lower.contains("overloaded");
    }

    private static boolean isSchemaValidationError(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        return lower.contains("tool call validation failed")
                || lower.contains("did not match schema")
                || lower.contains("value must be one of")
                || lower.contains("json schema")
                || lower.contains("invalid_tool");
    }
}
