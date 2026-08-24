package com.amsha.core.config;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ContentType;
import dev.langchain4j.data.message.CustomMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;

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
import com.amsha.core.governance.ApprovalFrame;
import com.amsha.core.routing.ModelCacheInvalidator;
import com.amsha.core.routing.ModelProfileCredentialResolver;
import com.amsha.core.routing.ModelProfileResolverImpl;
import com.amsha.core.routing.ModelRouter;
import com.amsha.core.routing.ModelRoutingException;
import com.amsha.core.routing.OrderedFailoverChatModel;
import com.amsha.core.routing.ResolvedCredential;
import com.amsha.core.routing.ResolvedProfile;
import com.amsha.core.routing.RouteResolution;
import com.amsha.core.routing.SystemProfileMigration;
import com.amsha.core.routing.provider.NetworkPolicyEnforcer;
import com.amsha.core.sandbox.SandboxResult;
import com.amsha.core.security.ExternalIdentityProvider;
import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.core.skill.CompiledSkillEntry;
import com.amsha.core.skill.SkillDefinition;
import com.amsha.core.skill.SkillFrontmatter;
import com.amsha.core.skill.SkillPromptParams;
import com.amsha.core.tools.CheckList;
import com.amsha.core.tools.CheckListEvent;
import com.amsha.core.tools.CheckListItem;
import com.amsha.core.tools.CheckListStatus;
import com.amsha.core.tools.ToolDiscoveryCompleted;
import com.amsha.core.tools.ToolRegistry;
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
            ApprovalFrame.class,
            SkillDefinition.class,
            SkillFrontmatter.class,
            CompiledSkillEntry.class,
            SkillPromptParams.class,
            SandboxResult.class,
            AgentJobCompleted.class,
            CronTaskScheduled.class,
            ReasoningStepDeserializer.class,
            ReasoningStepSerializer.class,
            CheckList.class,
            CheckListItem.class,
            CheckListStatus.class,
            CheckListEvent.class,
            ToolDiscoveryCompleted.class,
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
            NetworkPolicyEnforcer.class,
            GroupConsultationEngine.GroupConsultationResult.class,
            GroupConsultationService.TriageResult.class,
            GroupAnalyticsService.DomainComboStats.class,
            TaskClassifierService.TaskDomain.class,
            SessionCatalogEntry.class,
            ChatMessage.class,
            UserMessage.class,
            AiMessage.class,
            SystemMessage.class,
            ToolExecutionResultMessage.class,
            CustomMessage.class,
            ChatMessageType.class,
            ToolExecutionRequest.class,
            Content.class,
            ContentType.class,
            TextContent.class,
            ImageContent.class,
            AudioContent.class,
            VideoContent.class,
            PdfFileContent.class,
            OutboundHttpPolicyService.class,
            ExternalIdentityProvider.class
        },
        classNames = {
            "com.github.benmanes.caffeine.cache.SSLMSA",
            "com.github.benmanes.caffeine.cache.SSLMS",
            "com.github.benmanes.caffeine.cache.SSMS",
            "com.github.benmanes.caffeine.cache.SSMSA",
            // Quarkus LangChain4j's native ChatMessageSerializer substitution
            // uses these package-private Jackson mixins. In particular,
            // ChatMessageMixin carries the @JsonTypeInfo/@JsonProperty
            // metadata that emits the persisted message-level "type" field.
            "io.quarkiverse.langchain4j.QuarkusJsonCodecFactory$ChatMessageMixin",
            "io.quarkiverse.langchain4j.QuarkusJsonCodecFactory$AiMessageMixin",
            "io.quarkiverse.langchain4j.QuarkusJsonCodecFactory$UserMessageMixin",
            "io.quarkiverse.langchain4j.QuarkusJsonCodecFactory$SystemMessageMixin",
            "io.quarkiverse.langchain4j.QuarkusJsonCodecFactory$ToolExecutionResultMessageMixin",
            "io.quarkiverse.langchain4j.QuarkusJsonCodecFactory$CustomMessageMixin",
            "io.quarkiverse.langchain4j.QuarkusJsonCodecFactory$ToolExecutionRequestMixin"
        })
public class CoreReflectionConfig {}
