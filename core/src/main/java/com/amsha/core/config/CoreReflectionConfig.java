package com.amsha.core.config;

import com.amsha.core.agent.loop.GroupAnalyticsService;
import com.amsha.core.agent.loop.GroupConsultationEngine;
import com.amsha.core.agent.loop.GroupConsultationService;
import com.amsha.core.agent.loop.TaskClassifierService;
import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.reasoning.ReasoningStepDeserializer;
import com.amsha.core.agent.reasoning.ReasoningStepSerializer;
import com.amsha.core.agent.reasoning.error.LlmModelExhaustedException;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.api.AgentResponseMapper;
import com.amsha.core.api.dto.AmshaErrorResponse;
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
import com.amsha.core.routing.CodexOAuthTokenExtractor;
import com.amsha.core.routing.CodexResponsesChatModel;
import com.amsha.core.routing.CodexTokenResolver;
import com.amsha.core.routing.ModelCacheInvalidator;
import com.amsha.core.routing.ModelDefaults;
import com.amsha.core.routing.ModelProfileCredentialResolver;
import com.amsha.core.routing.ModelProfileResolverImpl;
import com.amsha.core.routing.ModelRouter;
import com.amsha.core.routing.ModelRoutingException;
import com.amsha.core.routing.OrderedFailoverChatModel;
import com.amsha.core.routing.ResolvedCredential;
import com.amsha.core.routing.ResolvedProfile;
import com.amsha.core.routing.RouteResolution;
import com.amsha.core.routing.SystemProfileMigration;
import com.amsha.core.routing.provider.ConnectionValidationRequest;
import com.amsha.core.routing.provider.LlmProviderDescriptor;
import com.amsha.core.routing.provider.LlmProviderOnboardingAdapter;
import com.amsha.core.routing.provider.ModelEntry;
import com.amsha.core.routing.provider.NetworkPolicyEnforcer;
import com.amsha.core.routing.provider.NetworkPolicyViolation;
import com.amsha.core.routing.provider.NetworkProfilePolicy;
import com.amsha.core.routing.provider.ProviderValidationResult;
import com.amsha.core.routing.provider.StaticProviderCatalogAdapter;
import com.amsha.core.sandbox.SandboxResult;
import com.amsha.core.security.ExternalIdentityProvider;
import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.core.security.OutboundPolicyDecision;
import com.amsha.core.security.OutboundPurpose;
import com.amsha.core.security.http.ApprovalReceiptIssuer;
import com.amsha.core.security.http.GovernedHttpClient;
import com.amsha.core.security.http.GovernedTransportService;
import com.amsha.core.security.http.OutboundApprovalReceipt;
import com.amsha.core.security.http.OutboundHttpException;
import com.amsha.core.skill.CompiledSkillEntry;
import com.amsha.core.skill.SkillAdmissionEvaluator;
import com.amsha.core.skill.SkillDefinition;
import com.amsha.core.skill.SkillFrontmatter;
import com.amsha.core.skill.SkillLoader;
import com.amsha.core.skill.SkillPromptParams;
import com.amsha.core.tools.GithubTool;
import com.amsha.core.tools.ManageScheduleTool;
import com.amsha.core.tools.SandboxExecTool;
import com.amsha.core.tools.ScheduleTaskTool;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.tools.ToolResponse;
import com.amsha.persistence.api.SessionCatalogEntry;

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
            AmshaErrorResponse.class,
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
            SkillAdmissionEvaluator.AdmissionDecision.class,
            SkillLoader.QuarantinedSkill.class,
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
            OrderedFailoverChatModel.class,
            OrderedFailoverChatModel.Entry.class,
            ResolvedProfile.class,
            RouteResolution.class,
            ResolvedCredential.class,
            ModelProfileResolverImpl.class,
            ModelProfileCredentialResolver.class,
            ModelRoutingException.class,
            SystemProfileMigration.class,
            ModelCacheInvalidator.class,
            ModelDefaults.class,
            LlmProviderDescriptor.class,
            ModelEntry.class,
            LlmProviderOnboardingAdapter.class,
            StaticProviderCatalogAdapter.class,
            ConnectionValidationRequest.class,
            ProviderValidationResult.class,
            NetworkPolicyEnforcer.class,
            NetworkPolicyViolation.class,
            NetworkProfilePolicy.class,
            LlmModelExhaustedException.class,
            GroupConsultationEngine.GroupConsultationResult.class,
            GroupConsultationService.TriageResult.class,
            GroupAnalyticsService.DomainComboStats.class,
            TaskClassifierService.TaskDomain.class,
            SessionCatalogEntry.class,
            OutboundHttpPolicyService.class,
            OutboundPolicyDecision.class,
            OutboundPurpose.class,
            ApprovalReceiptIssuer.class,
            OutboundApprovalReceipt.class,
            GovernedHttpClient.class,
            GovernedTransportService.class,
            OutboundHttpException.class,
            ExternalIdentityProvider.class,
            CodexOAuthTokenExtractor.class,
            CodexTokenResolver.class,
            CodexResponsesChatModel.class
        },
        classNames = {
            "com.amsha.core.routing.provider.StaticProviderCatalogAdapter$RawProvider",
            "com.github.benmanes.caffeine.cache.SSLMSA",
            "com.github.benmanes.caffeine.cache.SSLMS",
            "com.github.benmanes.caffeine.cache.SSMS",
            "com.github.benmanes.caffeine.cache.SSMSA"
        })
public class CoreReflectionConfig {}
