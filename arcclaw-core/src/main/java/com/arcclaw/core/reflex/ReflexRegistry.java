package com.arcclaw.core.reflex;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.core.audit.AuditHashUtil;
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

    @Inject
    AuditHashUtil hashUtil;

    public ReflexResult processIfReflex(String sessionId, String userId, String chatId,
                                         String input, MessageContext context) {

        if (input == null || input.isBlank()) {
            return new ReflexResult(Optional.empty(), input != null ? input : "");
        }

        // Vault-format guard: "store KEY=VALUE" — skip Pass 1 matching only.
        // Free-text ingest ("store my phone num ...") has no '=' so it falls through
        // to normal Pass 1 + Pass 2 processing including PII sanitization.
        // Pass 2 sanitization runs for vault commands too — vault values may contain PII.
        boolean isVaultCommand = input.trim().toLowerCase().startsWith("store ")
                && input.contains("=");

        // ── PASS 1: Command / reflex matching on RAW input ────────────────────
        if (!isVaultCommand) {
            for (AgentReflex reflex : reflexes) {
                if (reflex.matches(input)) {
                    try {
                        ReflexResult result = reflex.execute(sessionId, userId, chatId, input, context);

                        SecurityEventType auditType;
                        if (reflex instanceof PromptInjectionReflex) {
                            auditType = SecurityEventType.PROMPT_INJECTION_BLOCKED;
                            auditService.record(sessionId, userId, "reflex", auditType,
                                "inputHash=" + hashUtil.sha256hex(
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
                            String reflexName = reflex.getClass().getSimpleName()
                                .replace("_ClientProxy", "")
                                .replace("_Subclass", "");
                            auditService.record(sessionId, userId, "reflex", auditType,
                                "command=" + reflexName);
                        }

                        // Pass 1 short-circuit — LLM is bypassed, return immediately
                        return result;

                    } catch (Exception e) {
                        String sanitizedInput = input.length() > 200
                            ? input.substring(0, 200) + "...[truncated]"
                            : input;
                        log.errorf("[REFLEX-FAILURE] reflex=%s session=%s user=%s input=%s error=%s",
                            reflex.getClass().getSimpleName(), sessionId, userId,
                            sanitizedInput, e.getMessage());
                        log.debugf(e, "Full stacktrace for reflex failure: %s",
                            reflex.getClass().getSimpleName());
                        return ReflexResult.respond("⚠️ System Error during reflex execution: "
                            + e.getMessage(), context);
                    }
                }
            }
        }

        // ── PASS 2: Sanitization — ALWAYS runs, including vault commands ─────
        // Vault writes like "store GITHUB_TOKEN=ghp_xxx" may embed PII — always redact.
        // Free-text ingest ("store my phone num 4948483938") must be sanitized before
        // the coordinator's ingest_document tool persists it into RAG.
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