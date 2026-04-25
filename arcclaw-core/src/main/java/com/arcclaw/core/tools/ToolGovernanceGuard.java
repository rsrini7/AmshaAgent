package com.arcclaw.core.tools;

import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.RoleRegistry;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.governance.RequiresApprovalException;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.security.AutonomyPolicy;
import com.arcclaw.core.security.ToolOperation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

/**
 * Enforces role-capability, autonomy policy, and approval requirements for tool invocation.
 */
@ApplicationScoped
public class ToolGovernanceGuard {

    private static final Logger log = Logger.getLogger(ToolGovernanceGuard.class);

    @Inject AuditService auditService;
    @Inject RoleRegistry roleRegistry;
    @Inject ObjectMapper objectMapper;

    private final AutonomyPolicy autonomyPolicy = new AutonomyPolicy.Standard();

    public void verifyRoleCapability(String toolName, String agentRole) {
        if (agentRole == null) return;

        if (!AgentRoleEnum.isKnownRole(agentRole)) {
            log.warnf("SECURITY: Unknown agentRole=%s — blocking tool=%s", agentRole, toolName);
            throw new SecurityException("Unknown agent role: " + agentRole);
        }

        AgentRoleEnum role = AgentRoleEnum.fromString(agentRole);
        Set<String> allowedTools = roleRegistry.permittedTools(role);
        if (!allowedTools.contains(toolName)) {
            log.warnf("SECURITY: role=%s attempted tool=%s — not in capability set. Blocked.",
                    agentRole, toolName);
            auditService.record(
                    WorkspaceContext.requireSessionId(), resolveUserId(), agentRole,
                    SecurityEventType.TOOL_BLOCKED,
                    "tool=" + toolName + " reason=ROLE_CAPABILITY_MISMATCH role=" + agentRole);
            throw new SecurityException(
                    "Tool '" + toolName + "' is not available for role '" + agentRole + "'. "
                    + "This action requires a different agent role.");
        }
    }

    public void verifyAutonomy(String toolName, ToolOperation requiredOp, Map<String, Object> params) {
        AutonomyLevel level = WorkspaceContext.requireAutonomyOrSupervised();

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
                            || requiredOp == ToolOperation.EXECUTE_SHELL)) {
                log.infof("Tool '%s' requires approval at level %s (operation=%s)",
                          toolName, level, requiredOp);
                try {
                    String paramsJson = objectMapper.writeValueAsString(params);
                    throw new RequiresApprovalException(
                        toolName, paramsJson, WorkspaceContext.requireSessionId());
                } catch (JsonProcessingException e) {
                    throw new RuntimeException("Failed to serialize tool parameters", e);
                }
            }

            log.warnf("Tool '%s' blocked: operation %s not allowed at autonomy level %s",
                    toolName, requiredOp, level);
            auditService.record(
                    WorkspaceContext.requireSessionId(), resolveUserId(),
                    WorkspaceContext.requireAgentRole(),
                    SecurityEventType.TOOL_BLOCKED,
                    "tool=" + toolName + " op=" + requiredOp);
            throw new SecurityException(
                    "Operation " + requiredOp + " not permitted at autonomy level " + level);
        }
    }

    public void recordToolAudit(String toolName) {
        auditService.record(
                WorkspaceContext.requireSessionId(), resolveUserId(),
                WorkspaceContext.requireAgentRole(),
                SecurityEventType.TOOL_INVOKED,
                "tool=" + toolName);
    }

    public void recordToolFailureAudit(String toolName, ToolResponse response) {
        String details = String.format("tool=%s errorCode=%s traceId=%s rootCause=%s",
                toolName, response.errorCode(), response.traceId(), response.rootCauseClass());
        auditService.record(
                WorkspaceContext.requireSessionId(), resolveUserId(),
                WorkspaceContext.requireAgentRole(),
                SecurityEventType.TOOL_FAILED,
                details);
    }

    private String resolveUserId() {
        return WorkspaceContext.resolveUserId();
    }
}
