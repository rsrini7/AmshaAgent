package com.arcclaw.core.reflex;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.audit.AuditHashUtil;

import java.util.Optional;

@ApplicationScoped
public class ReflexRegistry {

    @Inject
    Logger log;

    @Inject
    Instance<AgentReflex> reflexes;

    @Inject
    AuditService auditService;

    public ReflexResult processIfReflex(String sessionId, String userId, String chatId, String input) {
        // Guard: if input starts with "store " treat as vault command — skip PII entirely
        if (input == null || input.isBlank() || input.trim().toLowerCase().startsWith("store ")) {
            // Route directly to vault handler — do not sanitize
            // Return empty Optional so TelegramAgentHandler routes to vault logic
            return new ReflexResult(Optional.empty(), input != null ? input : "");
        }

        // PASS 1: Command/reflex matching on RAW input FIRST
        for (AgentReflex reflex : reflexes) {
            if (reflex.matches(input)) {
                try {
                    String response = reflex.execute(sessionId, userId, chatId, input);  // ← chatId added

                    SecurityEventType auditType;
                    if (reflex instanceof PromptInjectionReflex) {
                        auditType = SecurityEventType.PROMPT_INJECTION_BLOCKED;
                        auditService.record(sessionId, userId, "reflex", auditType,
                            "inputHash=" + AuditHashUtil.sha256hex(
                                input.substring(0, Math.min(200, input.length()))));
                    } else if (reflex.getClass().getSimpleName().equals("ApproveReflex")) {
                        auditType = SecurityEventType.APPROVAL_GRANTED;
                        auditService.record(sessionId, userId, "reflex", auditType,
                            "action=APPROVE session=" + sessionId);
                    } else if (reflex.getClass().getSimpleName().equals("DenyReflex")) {
                        auditType = SecurityEventType.APPROVAL_DENIED;
                        auditService.record(sessionId, userId, "reflex", auditType,
                            "action=DENY session=" + sessionId);
                    } else {
                        auditType = SecurityEventType.REFLEX_COMMAND;
                        auditService.record(sessionId, userId, "reflex", auditType,
                            "command=" + reflex.getClass().getSimpleName());
                    }

                    // Pass 1: Command short-circuit — sanitizedInput is unused since LLM is bypassed
                    return new ReflexResult(Optional.ofNullable(response), input);
                } catch (Exception e) {
                    String sanitizedInput = input.length() > 200
                        ? input.substring(0, 200) + "...[truncated]"
                        : input;
                    log.errorf("[REFLEX-FAILURE] reflex=%s session=%s user=%s input=%s error=%s",
                        reflex.getClass().getSimpleName(), sessionId, userId,
                        sanitizedInput, e.getMessage(), e);
                    return new ReflexResult(
                        Optional.of("⚠️ System Error during reflex execution: " + e.getMessage()),
                        input);
                }
            }
        }

        // Pass 2: Sanitization (chain output through all reflexes)
        String sanitized = input;
        for (AgentReflex reflex : reflexes) {
            try {
                String beforeSanitize = sanitized;
                sanitized = reflex.sanitize(sessionId, userId, sanitized);

                if (reflex instanceof PiiScanReflex && !sanitized.equals(beforeSanitize)) {
                    auditService.record(sessionId, userId, "reflex",
                        SecurityEventType.PII_SCAN_REDACTED,
                        "mode=REDACT_AND_FORWARD inputLength=" + beforeSanitize.length());
                }
            } catch (Exception e) {
                log.errorf("[REFLEX-SANITIZE-FAILURE] reflex=%s error=%s",
                    reflex.getClass().getSimpleName(), e.getMessage(), e);
            }
        }

        return new ReflexResult(Optional.empty(), sanitized);
    }
}
