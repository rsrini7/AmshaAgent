package com.arcclaw.core.routing;

import java.util.Objects;

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

    public FallbackChatModel(ChatModel primary, ChatModel fallback, String primaryName, String fallbackName) {
        this.primary = Objects.requireNonNull(primary);
        this.fallback = Objects.requireNonNull(fallback);
        this.primaryName = primaryName;
        this.fallbackName = fallbackName;
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        try {
            return primary.chat(chatRequest);
        } catch (RuntimeException e) {
            if (isRateLimitException(e)) {
                log.warnf("[ArcClaw] ⚠️ Primary model %s rate-limited, falling back to %s", 
                         primaryName, fallbackName);
                return fallback.chat(chatRequest);
            }
            throw e;
        }
    }

    private boolean isRateLimitException(Throwable e) {
        if (e == null) return false;
        String msg = e.getMessage();
        if (msg != null && (msg.contains("429") || msg.toLowerCase().contains("rate limit"))) {
            return true;
        }
        return isRateLimitException(e.getCause());
    }
}
