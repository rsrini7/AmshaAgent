package com.arcclaw.core.reflex;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.pipeline.MessageContext;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ReflexRegistry {

    @Inject
    Logger log;

    @Inject
    Instance<AgentReflex> reflexes;

    @Inject
    AuditService auditService;

    public ReflexResult processIfReflex(String sessionId, String userId, String chatId,
                                        String input, MessageContext context) {

        // PASS 1 — find first matching reflex and dispatch
        for (AgentReflex reflex : reflexes) {
            if (!reflex.matches(input)) continue;
            try {
                auditService.record(
                    sessionId, userId,
                    reflex.getClass().getSimpleName()
                        .replace("_ClientProxy", "").replace("_Subclass", ""),
                    reflex.auditEventType(),
                    reflex.auditDetail(sessionId, input));
                return reflex.execute(sessionId, userId, chatId, input, context);
            } catch (Exception e) {
                String truncated = input != null && input.length() > 200
                    ? input.substring(0, 200) + "…" : input;
                log.errorf("REFLEX-FAILURE reflex=%s session=%s user=%s input=%s error=%s",
                    reflex.getClass().getSimpleName(), sessionId, userId, truncated,
                    e.getMessage());
                return ReflexResult.respond("System Error: " + e.getMessage(), context);
            }
        }

        // PASS 2 — no reflex matched; run sanitization chain
        String sanitized = input;
        for (AgentReflex reflex : reflexes) {
            try {
                String before = sanitized;
                sanitized = reflex.sanitize(sessionId, userId, sanitized);
                if (reflex.emitsSanitizeAudit() && !sanitized.equals(before)) {
                    auditService.record(
                        sessionId, userId,
                        reflex.getClass().getSimpleName()
                            .replace("_ClientProxy", "").replace("_Subclass", ""),
                        SecurityEventType.PII_SCAN_REDACTED,
                        reflex.auditDetail(sessionId, before));
                }
            } catch (Exception e) {
                log.errorf("REFLEX-SANITIZE-FAILURE reflex=%s session=%s error=%s",
                    reflex.getClass().getSimpleName(), sessionId, e.getMessage(), e);
            }
        }
        return new ReflexResult(Optional.empty(), sanitized);
    }
}