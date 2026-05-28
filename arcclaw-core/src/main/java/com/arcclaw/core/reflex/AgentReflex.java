package com.arcclaw.core.reflex;

import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.util.ClassUtils;

public interface AgentReflex {
    /**
     * Evaluates if the input triggers this reflex.
     */
    boolean matches(String input);

    /**
     * Execution priority for Pass 1 matching. Lower value means higher priority.
     * Prompt injection should usually be first (e.g. 10).
     */
    default int priority() {
        return 100;
    }

    /**
     * Executes the deterministic logic and returns the structured result.
     */
    ReflexResult execute(String sessionId, String userId, String chatId, String input, MessageContext context);

    /**
     * Sanitizes the input (e.g., PII redaction) before it reaches the LLM.
     * Defaults to a no-op returning the input unchanged.
     */
    default String sanitize(String sessionId, String userId, String input) {
        return input;
    }

    /**
     * Compile-safe audit event type this reflex produces when it fires.
     * Default covers all simple reflex reflexes (status, history, reset, halt, cron, etc.)
     */
    default SecurityEventType auditEventType() {
        return SecurityEventType.REFLEX_COMMAND;
    }

    /**
     * Audit detail string. Reflex owns the shape; registry never builds strings.
     * Default uses the reflex class name stripped of CDI proxy suffixes.
     */
    default String auditDetail(String sessionId, String input) {
        return "reflex=" + ClassUtils.getCleanName(this);
    }

    /**
     * Return true if a change in sanitize() output should trigger a
     * PII_SCAN_REDACTED audit record. False by default.
     */
    default boolean emitsSanitizeAudit() {
        return false;
    }
}
