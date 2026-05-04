package com.arcclaw.core.tools;

import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
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

    @Inject AutonomyPolicy autonomyPolicy;
    @Inject AuditService auditService;
    @Inject ObjectMapper objectMapper;

    /**
     * Verifies if the active agent role is allowed to execute the given tool.
     * 
     * @param ctx      Execution context.
     * @param toolName Tool name to check.
     * @throws SecurityException if role does not have the required capability.
     */
    public void verifyRoleCapability(ToolExecutionContext ctx, String toolName) {
        String roleName = ctx.agentRole();
        AgentRoleEnum role = AgentRoleEnum.fromString(roleName);
        Set<ToolCapability> capabilities = role.definition().capabilities();
        boolean allowed = capabilities.stream()
                .anyMatch(cap -> cap.toolName().equals(toolName));

        if (!allowed) {
            log.warnf("Role '%s' (session=%s) attempt to call unauthorized tool: %s",
                    roleName, ctx.sessionId(), toolName);
            recordToolBlockedAudit(ctx, toolName, "unauthorized_for_role=" + roleName);
            throw new SecurityException("Agent role '" + roleName + "' is not permitted to use tool '" + toolName + "'");
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
    public void verifyAutonomy(ToolExecutionContext ctx, String toolName, ToolOperation requiredOp, Map<String, Object> params) {
        AutonomyLevel level = ctx.autonomyLevel();

        // 1. HARD BLOCK check: if the policy says "not allowed" (e.g., READONLY level),
        // we throw SecurityException (403).
        if (!autonomyPolicy.isAllowed(level, requiredOp)) {
            
            // 2. HITL EXEMPTION: schedule_task and manage_schedule bypass approval
            // checks in SUPERVISED mode (they are allowed by policy anyway, but
            // we keep the check explicit to fulfill invariant §4.10).
            boolean isSchedulingTool = 
                    ToolCapability.SCHEDULE_TASK.toolName().equals(toolName)
                    || ToolCapability.MANAGE_SCHEDULE.toolName().equals(toolName);

            // 3. SUPERVISED SALVAGE: If it's blocked by policy but we are in SUPERVISED
            // mode, we might pivot to a PauseForApproval (409) instead of a hard 403.
            if (level == AutonomyLevel.SUPERVISED && !isSchedulingTool &&
                    (requiredOp == ToolOperation.NETWORK_OUTBOUND
                            || requiredOp == ToolOperation.VAULT_ACCESS
                            || requiredOp == ToolOperation.EXECUTE_SHELL
                            || requiredOp == ToolOperation.WRITE_MEMORY
                            || requiredOp == ToolOperation.DELEGATE_AUTONOMOUS
                            || requiredOp == ToolOperation.WRITE_FS)) {
                log.infof("Tool '%s' requires approval at level %s (operation=%s)",
                          toolName, level, requiredOp);
                
                ApprovalFrame frame = new ApprovalFrame(toolName, params, requiredOp.riskLevel());
                throw new RequiresApprovalException(frame, ctx.sessionId());
            }

            log.warnf("Tool '%s' blocked: operation %s not allowed at autonomy level %s",
                    toolName, requiredOp, level);
            recordToolBlockedAudit(ctx, toolName, "op=" + requiredOp);
            throw new SecurityException(
                    "Operation " + requiredOp + " not permitted at autonomy level " + level);
        }
    }

    public void recordToolAudit(ToolExecutionContext ctx, String toolName) {
        auditService.record(
                ctx.sessionId(), ctx.userId(),
                ctx.agentRole(),
                SecurityEventType.TOOL_EXECUTED,
                "tool=" + toolName);
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

        String details = String.format("tool=%s errorCode=%s traceId=%s rootCause=%s",
                toolName, errorCode, traceId, rootCause);
        auditService.record(
                ctx.sessionId(), ctx.userId(),
                ctx.agentRole(),
                SecurityEventType.TOOL_FAILED,
                details);
    }

    public void recordToolBlockedAudit(ToolExecutionContext ctx, String toolName, String reason) {
        auditService.record(
                ctx.sessionId(), ctx.userId(),
                ctx.agentRole(),
                SecurityEventType.TOOL_BLOCKED,
                "tool=" + toolName + " " + reason);
    }
}
