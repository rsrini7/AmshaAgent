package com.arcclaw.core.agent.reasoning.error;

import com.arcclaw.core.util.AgentErrorMessageUtil;

public class GenericExceptionClassifier extends LlmExceptionClassifierChain {

    @Override
    public LlmProviderException classify(Throwable t) {
        Throwable cause = t;
        while (cause != null) {
            if (cause instanceof IllegalArgumentException) {
                String msg = cause.getMessage();
                if (msg != null && (msg.contains("Invalid REST Client URL") || msg.contains("chat/completions"))) {
                    return new LlmServiceUnavailableException("Malformed LLM Base URL. " +
                            "Check if the provider's base URL is correct. " + msg, t);
                }
            }
            
            String msg = AgentErrorMessageUtil.safeMessage(cause);
            if (isRateLimitError(msg)) {
                return new LlmRateLimitException(msg, t);
            }

            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("timeout") 
                        || lower.contains("timed out")
                        || (lower.contains("exceeded") && lower.contains("ms"))) {
                    return new LlmServiceUnavailableException("LLM Request Timeout: " + msg, t);
                }
            }
            cause = cause.getCause();
        }
        
        String cleanMsg = AgentErrorMessageUtil.sanitizeErrorMessage(AgentErrorMessageUtil.safeMessage(t));
        return new LlmServiceUnavailableException("Unclassified error: " + cleanMsg, t);
    }
}
