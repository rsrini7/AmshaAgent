package com.arcclaw.core.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.arcclaw.core.scope.WorkspaceContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
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
        boolean isWriteIntent = WorkspaceContext.IS_WRITE_INTENT.isBound() 
                && WorkspaceContext.IS_WRITE_INTENT.get();
        boolean isCompact = WorkspaceContext.IS_COMPACT.isBound() 
                && WorkspaceContext.IS_COMPACT.get();

        boolean triedFallback = false;

        // ── Tier 1: Write-intent (Reliability Path) ──
        // Primary (Gemini/GPT) is most reliable for tool-calling (ingest_document).
        // Never preemptively downgrade. Fallback only if primary throws.
        if (isWriteIntent) {
            try {
                return primary.chat(chatRequest);
            } catch (Exception pe) {
                log.warnf("[ArcClaw] Primary failed on write-intent, using fallback '%s': %s",
                        fallbackName, pe.getMessage());
                try {
                    return fallback.chat(sanitizeRequest(chatRequest));
                } catch (Exception fe) {
                    log.errorf("[ArcClaw] ❌ Fallback model '%s' also failed on write-intent: %s", 
                            fallbackName, fe.getMessage());
                    throw fe;
                }
            }
        }

        // ── Tier 2: Compact/Chitchat (Efficiency Path) ──
        // Try fallback first - it's faster and cheaper for non-tool prose answers.
        if (isCompact) {
            triedFallback = true;
            try {
                return fallback.chat(sanitizeRequest(chatRequest));
            } catch (Exception fe) {
                log.warnf("[ArcClaw] Fallback failed on compact turn, promoting to primary: %s", 
                        fe.getMessage());
                // Fall through to primary
            }
        }

        // ── Tier 3: Large History Preemptive Reroute (Existing Logic) ──
        if (!triedFallback && chatRequest.messages().size() > messageThreshold) {
            triedFallback = true;
            log.warnf("[ArcClaw] ⚠️ History is large (%d), preemptively using fallback model '%s' to avoid safety filters.",
                    chatRequest.messages().size(), fallbackName);
            try {
                return fallback.chat(sanitizeRequest(chatRequest));
            } catch (Exception fe) {
                log.errorf("[ArcClaw] ❌ Fallback model '%s' failed on large history: %s", fallbackName, fe.getMessage());
                // Let it fall through to primary
            }
        }

        // ── Tier 4: Primary with Normal Exception Fallback ──
        try {
            return primary.chat(chatRequest);
        } catch (Throwable t) {
            // If we haven't already tried the fallback in this turn, try it now
            if (!triedFallback && isRetriableOnFallback(t)) {
                log.warnf("[ArcClaw] ⚠️ Primary model '%s' failed, falling back to '%s'. Cause: %s", 
                         primaryName, fallbackName, t.getMessage());
                try {
                    return fallback.chat(sanitizeRequest(chatRequest));
                } catch (Exception fe) {
                    log.errorf("[ArcClaw] ❌ Fallback model '%s' also failed: %s", fallbackName, fe.getMessage());
                    throw fe;
                }
            }
            if (t instanceof RuntimeException re) throw re;
            throw new RuntimeException(t);
        }
    }

    private ChatRequest sanitizeRequest(ChatRequest request) {
        if (request == null || request.messages() == null) return request;

        List<ChatMessage> original = request.messages();
        List<ChatMessage> sanitized = new ArrayList<>();

        for (int i = 0; i < original.size(); i++) {
            ChatMessage current = original.get(i);
            sanitized.add(current);

            // Adjacency check: if current is Tool result and next is User turn, inject Ai synthesis
            if (current instanceof ToolExecutionResultMessage && i < original.size() - 1) {
                ChatMessage next = original.get(i + 1);
                if (next instanceof UserMessage) {
                    log.debug("[ArcClaw] Sanitizing history: injecting synthetic assistant message between tool result and user turn.");
                    sanitized.add(AiMessage.from("I have completed the requested tool operations."));
                }
            }
        }

        if (sanitized.size() == original.size()) {
            return request;
        }

        return ChatRequest.builder()
                .messages(sanitized)
                .parameters(request.parameters())
                .build();
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

