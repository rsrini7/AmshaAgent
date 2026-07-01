package com.amsha.core.config;

import com.amsha.core.agent.loop.GroupAnalyticsService;
import com.amsha.core.agent.loop.GroupConsultationEngine;
import com.amsha.core.agent.loop.GroupConsultationService;
import com.amsha.core.agent.loop.TaskClassifierService;
import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.reasoning.ReasoningStepDeserializer;
import com.amsha.core.agent.reasoning.ReasoningStepSerializer;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.api.AgentResponseMapper;
import com.amsha.core.api.dto.AuditEventResponse;
import com.amsha.core.api.dto.CreateSessionRequest;
import com.amsha.core.api.dto.CreateSessionResponse;
import com.amsha.core.api.dto.RunAgentRequest;
import com.amsha.core.api.dto.SessionStatusResponse;
import com.amsha.core.audit.AuditOutcome;
import com.amsha.core.audit.AuditVerifier;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.cron.AgentJobCompleted;
import com.amsha.core.cron.CronTaskScheduled;
import com.amsha.core.routing.ModelRouter;
import com.amsha.core.sandbox.SandboxResult;
import com.amsha.core.skill.CompiledSkillEntry;
import com.amsha.core.skill.SkillDefinition;
import com.amsha.core.skill.SkillFrontmatter;
import com.amsha.core.skill.SkillPromptParams;
import com.amsha.core.tools.GithubTool;
import com.amsha.core.tools.ManageScheduleTool;
import com.amsha.core.tools.SandboxExecTool;
import com.amsha.core.tools.ScheduleTaskTool;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.tools.ToolResponse;

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
            AgentResponseMapper.AgentErrorResponse.class,
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
            AuditOutcome.class,
            SecurityEventType.class,
            GithubTool.class,
            SandboxExecTool.class,
            ScheduleTaskTool.class,
            ManageScheduleTool.class,
            SkillDefinition.class,
            SkillFrontmatter.class,
            CompiledSkillEntry.class,
            SkillPromptParams.class,
            SandboxResult.class,
            AgentJobCompleted.class,
            CronTaskScheduled.class,
            ToolResponse.class,
            ToolResponse.ToolSuccess.class,
            ToolResponse.ToolFailure.class,
            ToolResponse.ToolPending.class,
            ReasoningStepDeserializer.class,
            ReasoningStepSerializer.class,
            ModelRouter.ModelConfig.class,
            GroupConsultationEngine.GroupConsultationResult.class,
            GroupConsultationService.TriageResult.class,
            GroupAnalyticsService.DomainComboStats.class,
            TaskClassifierService.TaskDomain.class
        },
        classNames = {
            "com.github.benmanes.caffeine.cache.SSLMSA",
            "com.github.benmanes.caffeine.cache.SSLMS",
            "com.github.benmanes.caffeine.cache.SSMS",
            "com.github.benmanes.caffeine.cache.SSMSA"
        })
public class CoreReflectionConfig {}
