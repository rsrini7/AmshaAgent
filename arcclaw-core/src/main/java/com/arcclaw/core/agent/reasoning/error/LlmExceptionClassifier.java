package com.arcclaw.core.agent.reasoning.error;

import com.arcclaw.core.util.AgentErrorMessageUtil;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InvalidRequestException;

public class LlmExceptionClassifier {

    public static LlmProviderException classify(Throwable throwable) {
        if (throwable instanceof LlmProviderException lpe) {
            return lpe;
        }

        Throwable t = throwable;
        
        while (t != null) {
            if (t instanceof InvalidRequestException || (t instanceof HttpException && ((HttpException) t).statusCode() == 400)) {
                String msg = AgentErrorMessageUtil.safeMessage(t);
                
                if (isSchemaValidationError(msg)) {
                    return new LlmMalformedResponseException(msg, throwable);
                }
                
                if (msg != null && (msg.contains("tool_use_failed") || msg.contains("not in request.tools"))) {
                    return new LlmToolCallException(msg, throwable);
                }
                
                if (msg != null && (msg.contains("model_not_found") || msg.contains("does not exist") 
                        || msg.contains("invalid_api_key") || msg.contains("API_KEY_INVALID"))) {
                    return new LlmAuthenticationException(msg, throwable);
                }

                if (msg != null && (msg.toLowerCase().contains("degraded") || msg.toLowerCase().contains("cannot be invoked") || msg.toLowerCase().contains("overloaded"))) {
                    return new LlmServiceUnavailableException(msg, throwable);
                }
            }

            if (t instanceof HttpException) {
                int status = ((HttpException) t).statusCode();
                String msg = AgentErrorMessageUtil.safeMessage(t);
                if (status == 429 || isRateLimitError(msg)) {
                    return new LlmRateLimitException(msg != null ? msg : "Rate limit hit", throwable);
                }
                if (status == 401 || status == 403) {
                    return new LlmAuthenticationException(msg != null ? msg : "Authentication failed", throwable);
                }
                if (status >= 500) {
                    return new LlmServiceUnavailableException(msg != null ? msg : "Service unavailable", throwable);
                }
            }
            
            if (t instanceof NullPointerException) {
                String msg = t.getMessage();
                if (msg != null && (msg.contains("parts") || msg.contains("Part"))) {
                    return new LlmContentFilterException("Gemini returned an empty response or was blocked by safety filters. " +
                            "Try rephrasing your request or adjusting safety settings.", throwable);
                }
            }

            if (t instanceof IllegalArgumentException) {
                String msg = t.getMessage();
                if (msg != null && (msg.contains("Invalid REST Client URL") || msg.contains("chat/completions"))) {
                    return new LlmServiceUnavailableException("Malformed LLM Base URL. " +
                            "Check if the provider's base URL is correct. " + msg, throwable);
                }
            }
            
            String msg = AgentErrorMessageUtil.safeMessage(t);
            if (isRateLimitError(msg)) {
                return new LlmRateLimitException(msg, throwable);
            }

            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("timeout") 
                        || lower.contains("timed out")
                        || (lower.contains("exceeded") && lower.contains("ms"))) {
                    return new LlmServiceUnavailableException("LLM Request Timeout: " + msg, throwable);
                }
            }
            
            t = t.getCause();
        }
        
        String cleanMsg = AgentErrorMessageUtil.sanitizeErrorMessage(AgentErrorMessageUtil.safeMessage(throwable));
        return new LlmServiceUnavailableException("Unclassified error: " + cleanMsg, throwable);
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

    private static boolean isRateLimitError(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
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
