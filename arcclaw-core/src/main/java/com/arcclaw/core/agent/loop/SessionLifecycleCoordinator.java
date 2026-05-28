package com.arcclaw.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.util.AgentErrorMessageUtil;
import com.arcclaw.core.util.MdcKeys;

import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Manages MDC field setup/teardown and agent role resolution for the
 * ArcClaw reasoning loop.
 *
 * <p>Extracted from {@code AgentLoop.runWithRole} as part of Phase 2
 * God-class decomposition. Owns three of the seven original responsibilities:
 * <ul>
 *   <li><strong>MDC setup/teardown</strong> - puts and removes the
 *       {@code SESSION_ID}, {@code AGENT_ROLE}, and {@code USER_ID} keys
 *       so structured logging carries these fields on every line.</li>
 *   <li><strong>Role resolution / canonicalization</strong> - maps raw role
 *       strings to their canonical scoped names, with alias expansion and
 *       skill-role pass-through.</li>
 *   <li><strong>Compliance/audit on invalid roles</strong> - records a
 *       mandatory security event and increments a Micrometer counter before
 *       falling back to COORDINATOR.</li>
 * </ul>
 *
 * <h3>🚨 Transaction-free zone (§5.9)</h3>
 * <p>No method in this class may carry {@code @Transactional} or
 * {@code @Transactional(REQUIRED)}. This class sits on the path
 * {@code AgentDispatchHandler → AgentLoop → SessionLifecycleCoordinator};
 * acquiring a connection here silently reintroduces the P84 SQLITE_BUSY
 * cascade under concurrent approval + cron load.
 *
 * <h3>⚠️ WorkspaceContext, not WorkspaceScopeHolder (§2.3)</h3>
 * <p>All reads of {@code userId}, {@code chatId}, or {@code sessionId} inside
 * this class go through {@link WorkspaceContext} accessors.
 * {@code WorkspaceScopeHolder} must <strong>not</strong> be used here.
 *
 * <h3>⚠️ Alias handling stays inside resolution (§4.4)</h3>
 * <p>All role alias expansion is contained entirely within
 * {@link #resolveCanonicalRole(String, String)}. No other method in this class
 * may branch on, inspect, or expand role aliases.
 *
 * <p>After creation, verify no {@code @Transactional} leaked in:
 * <pre>grep -n "@Transactional" SessionLifecycleCoordinator.java</pre>
 * That must return nothing.
 */
@ApplicationScoped
public class SessionLifecycleCoordinator {

    private static final Logger log = Logger.getLogger(SessionLifecycleCoordinator.class);

    @Inject
    AuditService auditService;

    @Inject
    MeterRegistry meterRegistry;

    // ── MDC lifecycle ─────────────────────────────────────────────────────────

    /**
     * Populates MDC keys {@code SESSION_ID} and {@code AGENT_ROLE}, then
     * resolves the calling-thread {@code userId} from
     * {@link WorkspaceContext} (§2.3 - not {@code WorkspaceScopeHolder}) and
     * puts it under {@code USER_ID}.
     *
     * <p>The returned {@code userId} must be captured in a {@code final} local
     * variable by the caller <em>before</em> the {@code try} block so the
     * {@code finally} path can pass it to observers without re-reading MDC,
     * which is unreliable inside {@code finally}.
     *
     * <pre>{@code
     * // In AgentLoop.runWithRole:
     * final String capturedUserId = sessionLifecycleCoordinator.setupMdc(sessionId, canonicalRole);
     * AgentResult result = null;
     * try {
     *     // ... loop body
     * } finally {
     *     sessionLifecycleCoordinator.teardownMdc();
     *     observabilityManager.notifyLoopComplete(sessionId, capturedUserId, ...);
     * }
     * }</pre>
     *
     * @param sessionId    workspace session identifier
     * @param canonicalRole already-resolved canonical role name
     * @return {@code userId} from {@link WorkspaceContext#resolveUserId()};
     *         {@code "unknown"} if the workspace context is not yet bound
     */
    public String setupMdc(String sessionId, String canonicalRole) {
        // §Issue-5 Fix: resolve userId from context instead of hard-coding "unknown"
        String userId = WorkspaceContext.getUserIdOrUnknown();
        setupMdc(sessionId, canonicalRole, userId);
        return userId;
    }

    public String setupMdc(String sessionId, String canonicalRole, String userId) {
        MDC.put(MdcKeys.SESSION_ID, sessionId);
        MDC.put(MdcKeys.AGENT_ROLE, canonicalRole);
        MDC.put(MdcKeys.USER_ID, userId);
        return userId;
    }

    public void teardownMdc() {
        try {
            MDC.remove(MdcKeys.SESSION_ID);
            MDC.remove(MdcKeys.AGENT_ROLE);
            MDC.remove(MdcKeys.USER_ID);
        } catch (Exception e) {
            log.warnf(
                    "SessionLifecycleCoordinator.teardownMdc: failed to clear MDC keys: %s",
                    AgentErrorMessageUtil.safeMessage(e));
        }
    }

    public String resolveCanonicalRole(String sessionId, String agentRole, String userId) {
        if (!AgentRoleEnum.isKnownRole(agentRole)) {
            log.warnf(
                    "SessionLifecycleCoordinator: unknown agentRole '%s' for session %s - defaulting to COORDINATOR",
                    agentRole, sessionId);

            auditService.record(
                    sessionId,
                    userId,
                    agentRole,
                    SecurityEventType.INVALID_ROLE_FALLBACK,
                    "Unknown agentRole [" + agentRole + "] defaulted to COORDINATOR");

            Counter.builder("arcclaw.agent.role.unknown")
                    .tag("role", agentRole != null ? agentRole : "null")
                    .register(meterRegistry)
                    .increment();

            return AgentRoleEnum.COORDINATOR.toScopedName();
        }

        // §4.4 - alias expansion happens HERE, nowhere else.
        // AgentRoleEnum.fromString handles any configured aliases.
        AgentRoleEnum resolvedRole = AgentRoleEnum.fromString(agentRole);

        // Skill roles (e.g. "skill:code-reviewer") are kept verbatim as the
        // canonical key so each skill has its own independent checkpoint row
        // and conversation history.  Standard roles are normalised to their
        // scoped names (e.g. "coordinator", "researcher").
        return AgentRoleEnum.isSkillRole(agentRole)
                ? agentRole // skill:X - verbatim canonical key
                : resolvedRole.toScopedName(); // coordinator, researcher, …
    }
}
