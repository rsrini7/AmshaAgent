package com.arcclaw.core.routing;

import java.util.Objects;

import com.arcclaw.core.scope.WorkspaceContext;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.jboss.logging.Logger;

/**
 * A {@link ChatModel} wrapper that catches 429 Rate Limit errors from a primary model
 * and falls back to a second model.
 */
public class FallbackChatModel implements ChatModel {

    private static final Logger log = Logger.getLogger(FallbackChatModel.class);

    private final ChatModel primary;
    private final ChatModel fallback;
    private final String primaryName;
    private final String fallbackName;
    private final int messageThreshold;

    public FallbackChatModel(ChatModel primary, ChatModel fallback, String primaryName, String fallbackName, int messageThreshold) {
        this.primary = Objects.requireNonNull(primary);
        this.fallback = Objects.requireNonNull(fallback);
        this.primaryName = primaryName;
        this.fallbackName = fallbackName;
        this.messageThreshold = messageThreshold;
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        // 🟢 FIX 3: Never preemptively downgrade write-intent operations.
        // Fallback models (like Llama-4-Scout) lack reliable tool-calling for ingest_document
        // under heavy history pressure, whereas primary models (Gemini/GPT) are more robust.
        boolean isWriteIntent = WorkspaceContext.IS_WRITE_INTENT.isBound() 
                && WorkspaceContext.IS_WRITE_INTENT.get();

        if (chatRequest.messages().size() > messageThreshold && !isWriteIntent) {
            log.warnf("[ArcClaw] ⚠️ History is large (%d), preemptively using fallback model '%s' to avoid safety filters.",
                    chatRequest.messages().size(), fallbackName);
            try {
                return fallback.chat(chatRequest);
            } catch (Exception fe) {
                log.errorf("[ArcClaw] ❌ Fallback model '%s' failed on large history: %s", fallbackName, fe.getMessage());
                // Let it fall through to primary if fallback fails
            }
        }

        try {
            return primary.chat(chatRequest);
        } catch (Throwable t) {
            if (isRetriableOnFallback(t)) {
                log.warnf("[ArcClaw] ⚠️ Primary model '%s' failed, falling back to '%s'. Cause: %s", 
                         primaryName, fallbackName, t.getMessage());
                try {
                    return fallback.chat(chatRequest);
                } catch (Exception fe) {
                    log.errorf("[ArcClaw] ❌ Fallback model '%s' also failed: %s", fallbackName, fe.getMessage());
                    throw fe;
                }
            }
            if (t instanceof RuntimeException re) throw re;
            throw new RuntimeException(t);
        }
    }

    private boolean isRetriableOnFallback(Throwable e) {
        if (e == null) return false;

        if (e.getClass().getSimpleName().contains("ContentFilter")) {
            return true;
        }
        
        // 1. Explicit rate limit check
        String className = e.getClass().getSimpleName();
        if (className.contains("RateLimit")) return true;

        String msg = e.getMessage();
        if (msg != null) {
            String lower = msg.toLowerCase();
            // 2. HTTP 429 and common rate limit phrases
            if (lower.contains("429") || 
                lower.contains("rate limit") || 
                lower.contains("too many requests") ||
                lower.contains("rate_limit_exceeded") ||
                lower.contains("tokens per minute") ||
                lower.contains("tpm limit") ||
                lower.contains("rpm limit")) {
                return true;
            }

            if (lower.contains("blocked by safety filters")
                    || lower.contains("empty response")
                    || lower.contains("content filter")) {
                return true;
            }
             
            // 3. retriable 5xx errors (service unavailable/overloaded)
            if (lower.contains("502") || lower.contains("503") || lower.contains("504") ||
                lower.contains("service unavailable") || lower.contains("bad gateway") ||
                lower.contains("gateway timeout") || lower.contains("overloaded") ||
                lower.contains("timeout") || lower.contains("exceeded")) {
                return true;
            }

            // 4. Handle cases where 401/403 might be transient or misreported during high load
            if (lower.contains("401") || lower.contains("403")) {
                return true;
            }
        }

        // Recursive check for causes
        return isRetriableOnFallback(e.getCause());
    }
}
