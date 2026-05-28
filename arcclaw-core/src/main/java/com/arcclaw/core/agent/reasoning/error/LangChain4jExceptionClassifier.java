package com.arcclaw.core.agent.reasoning.error;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InvalidRequestException;

import com.arcclaw.core.util.AgentErrorMessageUtil;

public class LangChain4jExceptionClassifier extends LlmExceptionClassifierChain {

    @Override
    public LlmProviderException classify(Throwable t) {
        Throwable cause = t;
        while (cause != null) {
            if (cause instanceof InvalidRequestException
                    || (cause instanceof HttpException && ((HttpException) cause).statusCode() == 400)) {
                String msg = AgentErrorMessageUtil.safeMessage(cause);

                if (isSchemaValidationError(msg)) {
                    return new LlmMalformedResponseException(msg, t);
                }

                if (msg != null && (msg.contains("tool_use_failed") || msg.contains("not in request.tools"))) {
                    return new LlmToolCallException(msg, t);
                }

                if (msg != null
                        && (msg.contains("model_not_found")
                                || msg.contains("does not exist")
                                || msg.contains("invalid_api_key")
                                || msg.contains("API_KEY_INVALID"))) {
                    String hint = msg.contains("model_not_found") || msg.contains("does not exist")
                            ? " MODEL MAY HAVE BEEN REMOVED — check provider's model list and update ARCCLAW_*_MODEL."
                            : "";
                    return new LlmAuthenticationException(msg + hint, t);
                }

                if (msg != null
                        && (msg.toLowerCase().contains("degraded")
                                || msg.toLowerCase().contains("cannot be invoked")
                                || msg.toLowerCase().contains("overloaded"))) {
                    return new LlmServiceUnavailableException(msg, t);
                }
            }

            if (cause instanceof HttpException) {
                int status = ((HttpException) cause).statusCode();
                String msg = AgentErrorMessageUtil.safeMessage(cause);
                if (status == 429 || isRateLimitError(msg)) {
                    return new LlmRateLimitException(msg != null ? msg : "Rate limit hit", t);
                }
                if (status == 401 || status == 403) {
                    return new LlmAuthenticationException(msg != null ? msg : "Authentication failed", t);
                }
                if (status == 404) {
                    return new LlmAuthenticationException(
                            "Model not found (404). MODEL MAY HAVE BEEN REMOVED — check provider's model list and update ARCCLAW_*_MODEL. "
                                    + (msg != null ? msg : ""),
                            t);
                }
                if (status >= 500) {
                    return new LlmServiceUnavailableException(msg != null ? msg : "Service unavailable", t);
                }
            }
            cause = cause.getCause();
        }
        return delegate(t);
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
