package com.amsha.app.config;

import com.amsha.app.api.AdminDirectiveResource;
import com.amsha.app.api.ArchivalMemoryAdminResource;
import com.amsha.app.api.AuditResource;
import com.amsha.app.api.AuthResource;
import com.amsha.app.api.CoreMemoryAdminResource;
import com.amsha.app.api.IdentityResource;
import com.amsha.app.api.OnnxAdminResource;
import com.amsha.app.api.PromptAdminResource;
import com.amsha.app.api.RecallMemoryAdminResource;
import com.amsha.app.api.SessionResource;
import com.amsha.app.mcp.McpConnectResource;
import com.amsha.app.security.AuthErrorResponse;
import com.amsha.app.security.RateLimitErrorResponse;
import com.amsha.app.skill.SkillResource;
import com.amsha.app.ui.dto.AgentEvent;
import com.amsha.app.ui.dto.AgentStatusDto;
import com.amsha.app.ui.dto.ChatMessageDto;
import com.amsha.app.ui.dto.ChatRequest;
import com.amsha.app.ui.dto.CheckpointApprovalRequest;
import com.amsha.app.ui.dto.CheckpointApprovalResponse;
import com.amsha.app.ui.dto.PageDto;
import com.amsha.app.ui.dto.PendingApprovalDto;
import com.amsha.core.api.dto.ActiveAgentResponse;
import com.amsha.onboarding.BootstrapResource;
import com.amsha.persistence.api.ExternalIdentityDto;
import com.amsha.persistence.api.IdentityInvitationDto;
import com.amsha.persistence.api.IdentityUserDto;
import com.amsha.persistence.api.WorkspaceMembershipDto;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(
        targets = {
            McpConnectResource.ConnectRequest.class,
            McpConnectResource.ConnectResponse.class,
            McpConnectResource.DisconnectResponse.class,
            McpConnectResource.ServerEntry.class,
            SkillResource.MessageResponse.class,
            SkillResource.QuarantinedSkillResponse.class,
            SkillResource.SkillAdmissionResponse.class,
            SkillResource.SkillResponse.class,
            AuthErrorResponse.class,
            RateLimitErrorResponse.class,
            AgentEvent.class,
            AgentStatusDto.class,
            ChatRequest.class,
            CheckpointApprovalRequest.class,
            CheckpointApprovalResponse.class,
            PendingApprovalDto.class,
            PageDto.class,
            ChatMessageDto.class,
            AdminDirectiveResource.ReconcileResponse.class,
            OnnxAdminResource.ClassifierRuntimeResponse.class,
            OnnxAdminResource.ClassifierToggleResponse.class,
            ArchivalMemoryAdminResource.EvictResponse.class,
            RecallMemoryAdminResource.PurgeResponse.class,
            CoreMemoryAdminResource.CoreMemoryUpdateRequest.class,
            PromptAdminResource.PromptUpdateRequest.class,
            ActiveAgentResponse.class,
            // Identity / Access / Workspace Sharing / Bootstrap ──
            // Onboarding bootstrap records
            BootstrapResource.BootstrapStatusResponse.class,
            BootstrapResource.ClaimResponse.class,
            BootstrapResource.ConfirmResponse.class,
            BootstrapResource.ErrorResponse.class,
            // Identity resource records (response + request DTOs)
            IdentityResource.ErrorResponse.class,
            IdentityResource.MeResponse.class,
            IdentityResource.CreateInvitationRequest.class,
            IdentityResource.CreateInvitationResponse.class,
            IdentityResource.AcceptInvitationResponse.class,
            IdentityResource.CreateApiCredentialRequest.class,
            IdentityResource.CreateApiCredentialResponse.class,
            IdentityResource.RevokeResponse.class,
            IdentityResource.TransferOwnerRequest.class,
            IdentityResource.TransferOwnerResponse.class,
            IdentityResource.RotateCredentialsResponse.class,
            // Auth resource records
            AuthResource.ErrorResponse.class,
            AuthResource.LogoutResponse.class,
            // revoke-all-sessions moved to SessionResource
            SessionResource.RevokeAllResponse.class,
            AuditResource.class,
            SessionResource.class,
            // Persistence DTOs serialized inside identity responses
            IdentityUserDto.class,
            WorkspaceMembershipDto.class,
            ExternalIdentityDto.class,
            IdentityInvitationDto.class,
        })
public class AppReflectionConfig {}
