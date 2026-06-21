package com.arcclaw.core.tools;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.CapabilityRegistry;
import com.arcclaw.core.agent.role.RoleRegistry;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.config.PolicyConfig;
import com.arcclaw.core.governance.ApprovalFrame;
import com.arcclaw.core.governance.RequiresApprovalException;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.security.AutonomyPolicy;
import com.arcclaw.core.security.ToolOperation;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

/**
 * Validates role-based capability and autonomy-based execution permission.
 */
@ApplicationScoped
public class ToolGovernanceGuard {

    private static final Logger log = Logger.getLogger(ToolGovernanceGuard.class);

    @Inject
    AutonomyPolicy autonomyPolicy;

    @Inject
    AuditService auditService;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    CapabilityRegistry capabilityRegistry;

    @Inject
    RoleRegistry roleRegistry;

    @Inject
    PolicyConfig policyConfig;

    private Set<String> hitlExemptTools;
    private Set<ToolOperation> supervisedSalvageOps;

    @PostConstruct
    void init() {
        var gov = policyConfig.governance();
        this.hitlExemptTools = Set.copyOf(gov.hitlExemptTools());
        this.supervisedSalvageOps = gov.supervisedSalvageOperations().stream()
                .map(ToolOperation::valueOf)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Verifies if the active agent role is allowed to execute the given tool.
     *
     * @param ctx      Execution context.
     * @param toolName Tool name to check.
     * @throws SecurityException if role does not have the required capability.
     */
    public void verifyRoleCapability(ToolExecutionContext ctx, String toolName) {
        String roleName = ctx.agentRole();

        // Derive permission from the dynamic CapabilityRegistry (populated by @ToolMetadata scans)
        // rather than the hardcoded static sets in AgentRoleEnum. §T2-5
        boolean allowed = capabilityRegistry.hasCapability(roleName, toolName);

        if (!allowed) {
            log.warnf(
                    "Role '%s' (session=%s) attempt to call unauthorized tool: %s",
                    roleName, ctx.sessionId(), toolName);
            recordToolBlockedAudit(ctx, toolName, "unauthorized_for_role=" + roleName);
            throw new SecurityException(
                    "Agent role '" + roleName + "' is not permitted to use tool '" + toolName + "'");
        }
    }

    /**
     * Verifies if the current autonomy level allows the operation.
     *
     * @param ctx        Execution context.
     * @param toolName   Tool name.
     * @param requiredOp Operation category.
     * @param params     Parameters (for HITL frame).
     * @throws SecurityException if blocked by hard policy.
     * @throws RequiresApprovalException if in SUPERVISED mode and operation requires human approval.
     */
    public void verifyAutonomy(
            ToolExecutionContext ctx, String toolName, ToolOperation requiredOp, Map<String, Object> params) {
        AutonomyLevel level = ctx.autonomyLevel();

        // 0. ROLE TRUST: if the role has requiresApproval=false, it's a trusted execution role.
        // Trusted roles bypass operation policy checks for non-sensitive operations.
        // NETWORK_OUTBOUND (external search) and EXECUTE_SHELL (code execution) always require HITL.
        AgentRoleEnum roleEnum = AgentRoleEnum.fromString(ctx.agentRole());
        boolean isTrustedRole = !roleRegistry.requiresApprovalForTools(roleEnum);
        boolean isSensitiveOp =
                requiredOp == ToolOperation.NETWORK_OUTBOUND || requiredOp == ToolOperation.EXECUTE_SHELL;
        if (isTrustedRole && !isSensitiveOp) {
            return; // trusted role — allow non-sensitive operations
        }

        // 1. HARD BLOCK check: if the policy says "not allowed" (e.g., READONLY level),
        // we throw SecurityException (403).
        if (!autonomyPolicy.isAllowed(level, requiredOp)) {

            // 2. HITL EXEMPTION: configured tools bypass both approval AND hard block
            // for salvageable operations. The exemption means "trust this tool completely".
            boolean isHitlExempt = hitlExemptTools.contains(toolName);

            // 3. HITL EXEMPT + SALVAGEABLE: allow the tool to proceed without approval or block.
            if (isHitlExempt && supervisedSalvageOps.contains(requiredOp)) {
                log.debugf("Tool '%s' exempt from HITL at level %s (operation=%s)", toolName, level, requiredOp);
                return; // allowed — no approval, no block
            }

            // 4. SUPERVISED SALVAGE: If blocked by policy but in SUPERVISED mode,
            // pivot to PauseForApproval (409) instead of hard 403 for configured ops.
            if (level == AutonomyLevel.SUPERVISED && supervisedSalvageOps.contains(requiredOp)) {
                log.infof("Tool '%s' requires approval at level %s (operation=%s)", toolName, level, requiredOp);

                ApprovalFrame frame = new ApprovalFrame(toolName, params, requiredOp.riskLevel());
                throw new RequiresApprovalException(frame, ctx.sessionId());
            }

            log.warnf("Tool '%s' blocked: operation %s not allowed at autonomy level %s", toolName, requiredOp, level);
            recordToolBlockedAudit(ctx, toolName, "op=" + requiredOp);
            throw new SecurityException("Operation " + requiredOp + " not permitted at autonomy level " + level);
        }
    }

    public void recordToolAudit(ToolExecutionContext ctx, String toolName) {
        auditService.record(
                ctx.sessionId(), ctx.userId(), ctx.agentRole(), SecurityEventType.TOOL_EXECUTED, "tool=" + toolName);
    }

    public void recordToolFailureAudit(ToolExecutionContext ctx, String toolName, ToolResponse response) {
        String errorCode = null;
        String traceId = null;
        String rootCause = null;

        if (response instanceof ToolResponse.ToolFailure failure) {
            errorCode = failure.errorCode();
            traceId = failure.traceId();
            rootCause = failure.rootCauseClass();
        }

        String details =
                String.format("tool=%s errorCode=%s traceId=%s rootCause=%s", toolName, errorCode, traceId, rootCause);
        auditService.record(ctx.sessionId(), ctx.userId(), ctx.agentRole(), SecurityEventType.TOOL_FAILED, details);
    }

    public void recordToolBlockedAudit(ToolExecutionContext ctx, String toolName, String reason) {
        auditService.record(
                ctx.sessionId(),
                ctx.userId(),
                ctx.agentRole(),
                SecurityEventType.TOOL_BLOCKED,
                "tool=" + toolName + " " + reason);
    }
}
