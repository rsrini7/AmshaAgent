package com.arcclaw.core.config;

import com.arcclaw.core.agent.reasoning.ParallelToolCall;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.api.AgentResponseMapper;
import com.arcclaw.core.api.dto.AuditEventResponse;
import com.arcclaw.core.api.dto.CreateSessionRequest;
import com.arcclaw.core.api.dto.CreateSessionResponse;
import com.arcclaw.core.api.dto.RunAgentRequest;
import com.arcclaw.core.api.dto.SessionStatusResponse;
import com.arcclaw.core.audit.AuditEvent;
import com.arcclaw.core.audit.AuditOutcome;
import com.arcclaw.core.audit.AuditVerifier;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.cron.AgentJobCompleted;
import com.arcclaw.core.cron.CronTaskScheduled;
import com.arcclaw.core.persistence.ArcClawSQLiteDialect;
import com.arcclaw.core.sandbox.SandboxResult;
import com.arcclaw.core.skill.AgentSkillEntity;
import com.arcclaw.core.skill.AgentSkillRepository;
import com.arcclaw.core.skill.CompiledSkillEntry;
import com.arcclaw.core.skill.SkillDefinition;
import com.arcclaw.core.skill.SkillFrontmatter;
import com.arcclaw.core.skill.SkillPromptParams;
import com.arcclaw.core.state.AgentCronEntity;
import com.arcclaw.core.state.AgentCronRepository;
import com.arcclaw.core.state.InstantToTextConverter;
import com.arcclaw.core.tools.GithubTool;
import com.arcclaw.core.tools.ManageScheduleTool;
import com.arcclaw.core.tools.MemorySearchTool;
import com.arcclaw.core.tools.SandboxExecTool;
import com.arcclaw.core.tools.ScheduleTaskTool;
import com.arcclaw.core.tools.ToolRegistry;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Ensures critical DTOs and Agent models are protected from dead-code
 * elimination
 * and are fully available for reflection in GraalVM Native Image mode.
 */
@RegisterForReflection(
    targets = {
        AgentResponseMapper.AgentResponse.class,
        AgentResponseMapper.ApprovalRequiredResponse.class,
        AgentResponseMapper.ErrorResponse.class,
        AgentResult.Finished.class,
        AgentResult.AwaitingApproval.class,
        AgentResult.Failed.class,
        AgentResult.LoopExceeded.class,
        AgentResult.FailureCategory.class,
        SystemMetadataRegistry.SystemInfo.class,
        AuditVerifier.VerificationResult.class,
        ReasoningStep.class,
        ParallelToolCall.class,
        AgentResult.class,
        CreateSessionRequest.class,
        RunAgentRequest.class,
        CreateSessionResponse.class,
        SessionStatusResponse.class,
        AuditEventResponse.class,
        ToolRegistry.ToolDescriptor.class,
        AuditEvent.class,
        AuditOutcome.class,
        SecurityEventType.class,
        GithubTool.class,
        MemorySearchTool.class,
        AgentCronEntity.class,
        InstantToTextConverter.class,
        SandboxExecTool.class,
        ScheduleTaskTool.class,
        ManageScheduleTool.class,
        AgentCronRepository.class,
        AgentSkillEntity.class,
        AgentSkillRepository.class,
        SkillDefinition.class,
        SkillFrontmatter.class,
        CompiledSkillEntry.class,
        SkillPromptParams.class,
        ArcClawSQLiteDialect.class,
        SandboxResult.class,
        AgentJobCompleted.class,
        CronTaskScheduled.class
    },
    classNames = {
        "com.github.benmanes.caffeine.cache.SSLMSA",
        "com.github.benmanes.caffeine.cache.SSLMS",
        "com.github.benmanes.caffeine.cache.SSMS",
        "com.github.benmanes.caffeine.cache.SSMSA"
    }
)
public class CoreReflectionConfig {
}
