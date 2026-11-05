package com.amsha.app.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import java.util.Map;

import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.persistence.api.AuditEventStorePort;
import com.amsha.persistence.api.ToolAttemptStatus;
import com.amsha.persistence.api.ToolAttemptStorePort;
import com.amsha.security.api.ToolOperation;
import com.amsha.tool.api.AgentTool;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolMetadata;
import com.amsha.tool.api.ToolResponse;

/** Test-only executor inspects durable state at the real tool execution boundary. */
@ApplicationScoped
@Alternative
@ToolMetadata(
        capabilityId = ToolAttemptBoundaryProbe.TOOL_NAME,
        description = "Test probe for durable tool attempt ordering",
        defaultRoles = AgentRoleEnum.CODER_ID)
public class ToolAttemptBoundaryProbe implements AgentTool {
    public static final String TOOL_NAME = "tool_attempt_boundary_probe";

    private int invocationCount;

    @Inject
    ToolAttemptStorePort attemptStore;

    @Inject
    AuditEventStorePort auditStore;

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        return "Test probe for durable tool attempt ordering";
    }

    @Override
    public ToolOperation requiredOperation() {
        return ToolOperation.READ_FS;
    }

    @Override
    public boolean requiresSandbox() {
        return false;
    }

    public int invocationCount() {
        return invocationCount;
    }

    @Override
    public ToolResponse execute(ToolExecutionContext context, Map<String, Object> params) {
        invocationCount++;
        if (params == null || !params.isEmpty() || context.attemptId() == null) {
            return ToolResponse.infraFailure("Probe did not receive an admitted attempt.");
        }
        boolean running = attemptStore
                .findAttempt(context.workspaceId(), context.attemptId())
                .map(attempt -> attempt.status() == ToolAttemptStatus.RUNNING)
                .orElse(false);
        long admissions =
                auditStore.countEventsByType(context.sessionId(), SecurityEventType.TOOL_ATTEMPT_ADMITTED.name());
        long launches =
                auditStore.countEventsByType(context.sessionId(), SecurityEventType.TOOL_LAUNCH_REQUESTED.name());
        if (running && admissions == 1 && launches == 1) {
            return ToolResponse.success("pre-launch-gates-acknowledged");
        }
        return ToolResponse.infraFailure("Required pre-launch state was not durably acknowledged.");
    }
}
