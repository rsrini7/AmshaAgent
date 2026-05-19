package com.arcclaw.core.reflex;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.common.util.LogUtil;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.util.ClassUtils;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ReflexRegistry {

    @Inject
    Logger log;

    @Inject
    Instance<AgentReflex> reflexes;

    @Inject
    AuditService auditService;

    private List<AgentReflex> sortedReflexes;

    /**
     * Startup-sorted immutable cache. O(N log N) sort happens once at boot.
     * Hot-reload safety: Quarkus dev-mode restarts the CDI context on code
     * changes, so @PostConstruct re-fires and the cache is rebuilt automatically.
     * Per-request sorting was removed (RC4) because even though it guaranteed
     * snapshot consistency, the startup cache is equivalent in Quarkus's
     * restart-based hot-reload model.
     */
    @PostConstruct
    void init() {
        this.sortedReflexes = reflexes.stream()
            .sorted(Comparator.comparingInt(this::getPriority))
            .toList();
        log.infof("ReflexRegistry: Initialized with %d sorted reflexes.", sortedReflexes.size());
    }

    private int getPriority(AgentReflex reflex) {
        // Unwrap proxy for annotation check
        Class<?> actualClass = reflex.getClass();
        while (actualClass.getName().contains("_ClientProxy") || actualClass.getName().contains("_Subclass")) {
            actualClass = actualClass.getSuperclass();
        }
        ReflexPriority annotation = actualClass.getAnnotation(ReflexPriority.class);
        return annotation != null ? annotation.value() : reflex.priority();
    }

    public ReflexResult processIfReflex(String sessionId, String userId, String chatId,
                                        String input, MessageContext context) {

        // PASS 1 - find first matching reflex and dispatch
        for (AgentReflex reflex : sortedReflexes) {
            if (!reflex.matches(input)) continue;
            try {
                auditService.record(
                    sessionId, userId,
                    ClassUtils.getCleanName(reflex),
                    reflex.auditEventType(),
                    reflex.auditDetail(sessionId, input));
                return reflex.execute(sessionId, userId, chatId, input, context);
            } catch (Exception e) {
                String truncated = input != null && input.length() > 200
                    ? input.substring(0, 200) + "…" : input;
                log.errorf("REFLEX-FAILURE reflex=%s session=%s user=%s input=%s error=%s",
                    ClassUtils.getCleanName(reflex), sessionId, userId, truncated,
                    LogUtil.rootMessage(e));
                return ReflexResult.respond("System Error: " + e.getMessage(), context);
            }
        }

        // PASS 2 - no reflex matched; run sanitization chain
        String sanitized = input;
        for (AgentReflex reflex : sortedReflexes) {
            try {
                String before = sanitized;
                sanitized = reflex.sanitize(sessionId, userId, sanitized);
                if (reflex.emitsSanitizeAudit() && !Objects.equals(before, sanitized)) {
                    auditService.record(
                        sessionId, userId,
                        ClassUtils.getCleanName(reflex),
                        SecurityEventType.PII_SCAN_REDACTED,
                        reflex.auditDetail(sessionId, before));
                }
            } catch (Exception e) {
                log.errorf("REFLEX-SANITIZE-FAILURE reflex=%s session=%s error=%s",
                    ClassUtils.getCleanName(reflex), sessionId, LogUtil.rootMessage(e));
            }
        }
        return new ReflexResult(Optional.empty(), sanitized);
    }
}
