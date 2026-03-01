package com.arcclaw.core.config;

import org.hibernate.community.dialect.SQLiteDialect;

import com.arcclaw.core.agent.AgentResult;
import com.arcclaw.core.agent.ReasoningStep;
import com.arcclaw.core.api.dto.CreateSessionResponse;
import com.arcclaw.core.api.dto.RunAgentRequest;
import com.arcclaw.core.api.dto.SessionStatusResponse;
import com.arcclaw.core.api.dto.AuditEventResponse;
import com.arcclaw.core.audit.AuditEvent;
import com.arcclaw.core.audit.AuditOutcome;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.tools.GithubTool;
import com.arcclaw.core.tools.MemorySearchTool;
import com.arcclaw.core.tools.ScheduleTaskTool;
import com.arcclaw.core.tools.ManageScheduleTool;
import com.arcclaw.core.state.AgentCronEntity;
import com.arcclaw.core.state.AgentCronRepository;
import com.arcclaw.core.state.InstantToTextConverter;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Ensures critical DTOs and Agent models are protected from dead-code
 * elimination
 * and are fully available for reflection in GraalVM Native Image mode.
 */
@RegisterForReflection(targets = {
        ReasoningStep.class,
        AgentResult.class,
        RunAgentRequest.class,
        CreateSessionResponse.class,
        SessionStatusResponse.class,
        AuditEventResponse.class,
        AuditEvent.class,
        AuditOutcome.class,
        SecurityEventType.class,
        GithubTool.class,
        MemorySearchTool.class,
        AgentCronEntity.class,
        InstantToTextConverter.class,
        ScheduleTaskTool.class,
        ManageScheduleTool.class,
        AgentCronRepository.class,
        SQLiteDialect.class
})
public class ReflectionConfig {
}
