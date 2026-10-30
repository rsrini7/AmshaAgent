package com.amsha.app.config;

import com.amsha.app.api.AdminDirectiveResource;
import com.amsha.app.api.ArchivalMemoryAdminResource;
import com.amsha.app.api.AuditResource;
import com.amsha.app.api.AuthResource;
import com.amsha.app.api.CoreMemoryAdminResource;
import com.amsha.app.api.DbConsoleResource;
import com.amsha.app.api.DecisionAdminResource;
import com.amsha.app.api.IdentityResource;
import com.amsha.app.api.ModelArenaResource;
import com.amsha.app.api.OnnxAdminResource;
import com.amsha.app.api.OnnxModelAdminResource;
import com.amsha.app.api.PromptAdminResource;
import com.amsha.app.api.RecallMemoryAdminResource;
import com.amsha.app.api.SessionResource;
import com.amsha.app.cli.ModelInstaller;
import com.amsha.app.connector.github.GitHubAppCredentialStatusResponse;
import com.amsha.app.connector.github.GitHubReadmeDocumentStatusResponse;
import com.amsha.app.connector.github.GitHubReadmeStatusResponse;
import com.amsha.app.security.AuthErrorResponse;
import com.amsha.app.security.RateLimitErrorResponse;
import com.amsha.app.skill.SkillResource;
import com.amsha.app.ui.CockpitHealthResource;
import com.amsha.app.ui.CockpitResource;
import com.amsha.app.ui.dto.AgentEvent;
import com.amsha.app.ui.dto.AgentStatusDto;
import com.amsha.app.ui.dto.ArenaCompareRequest;
import com.amsha.app.ui.dto.ArenaCompareResponse;
import com.amsha.app.ui.dto.ArenaModelResult;
import com.amsha.app.ui.dto.ChatMessageDto;
import com.amsha.app.ui.dto.ChatRequest;
import com.amsha.app.ui.dto.CheckpointApprovalRequest;
import com.amsha.app.ui.dto.CheckpointApprovalResponse;
import com.amsha.app.ui.dto.CockpitHealthResponse;
import com.amsha.app.ui.dto.DbConnectionPoolSummary;
import com.amsha.app.ui.dto.DbQueryRequest;
import com.amsha.app.ui.dto.DbQueryResponse;
import com.amsha.app.ui.dto.HealthCheckDetail;
import com.amsha.app.ui.dto.PageDto;
import com.amsha.app.ui.dto.PendingApprovalDto;
import com.amsha.app.ui.dto.ProviderHealthSummary;
import com.amsha.app.ui.dto.RunDecisionEvidenceDto;
import com.amsha.app.ui.dto.RunEvidenceDto;
import com.amsha.app.ui.dto.SmallRyeHealthSummary;
import com.amsha.core.classifier.evaluation.DecisionEvidenceDiagnostics;
import com.amsha.core.classifier.evaluation.DecisionEvidenceRunAttribution;
import com.amsha.core.classifier.evaluation.DecisionEvidenceSummary;
import com.amsha.onboarding.BootstrapResource;
import com.amsha.persistence.api.ExternalIdentityDto;
import com.amsha.persistence.api.IdentityInvitationDto;
import com.amsha.persistence.api.IdentityUserDto;
import com.amsha.persistence.api.WorkspaceMembershipDto;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(
        classNames = {
            "com.amsha.app.mcp.McpConnectResource$ConnectRequest",
            "com.amsha.app.mcp.McpConnectResource$DisconnectResponse",
            "com.amsha.app.mcp.McpConnectResource$DiscoverRequest",
            "com.amsha.app.mcp.McpConnectResource$RotateHeadersRequest",
            "com.amsha.app.mcp.McpConnectResource$ReconfigureRequest",
            "com.amsha.app.mcp.McpImportResource$ApplyRequest",
            "com.amsha.app.mcp.McpSetupResource$IssueRequest",
            "com.amsha.app.mcp.McpSetupResource$RedeemRequest",
            "com.amsha.app.mcp.McpSetupResource$RedeemResponse",
            "com.amsha.app.api.IdentityResourceHelper",
            "com.amsha.app.api.IdentityResourceHelper$VerifiedEmail"
        },
        targets = {
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
            OnnxModelAdminResource.ModelUnloadResponse.class,
            ArchivalMemoryAdminResource.EvictResponse.class,
            RecallMemoryAdminResource.PurgeResponse.class,
            CoreMemoryAdminResource.CoreMemoryUpdateRequest.class,
            PromptAdminResource.PromptUpdateRequest.class,
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
            CockpitResource.class,
            CockpitHealthResource.class,
            DbConsoleResource.class,
            ModelArenaResource.class,
            // W4 decision evaluation surface: the operator DTOs cross the JSON boundary, so they must
            // be reachable reflectively in a native image. Registered centrally, per AGENTS.md §8.
            DecisionAdminResource.class,
            DecisionAdminResource.ProviderStatusResponse.class,
            DecisionAdminResource.EvaluateRequest.class,
            DecisionAdminResource.EvaluateResponse.class,
            // The evidence endpoint returns Response, so its dynamic JSON entity graph cannot be inferred
            // from the declared return type. Register the aggregate summary, attribution, and diagnostics
            // explicitly. A previous native run returned 500 for JSON attribution while Markdown succeeded,
            // which exposed this gap.
            DecisionEvidenceSummary.class,
            DecisionEvidenceRunAttribution.class,
            DecisionEvidenceDiagnostics.class,
            DecisionEvidenceDiagnostics.ProviderUsage.class,
            DbQueryRequest.class,
            DbQueryResponse.class,
            ArenaCompareRequest.class,
            ArenaCompareResponse.class,
            ArenaModelResult.class,
            CockpitHealthResponse.class,
            SmallRyeHealthSummary.class,
            HealthCheckDetail.class,
            DbConnectionPoolSummary.class,
            ProviderHealthSummary.class,
            RunDecisionEvidenceDto.class,
            RunEvidenceDto.class,
            GitHubAppCredentialStatusResponse.class,
            GitHubReadmeDocumentStatusResponse.class,
            GitHubReadmeStatusResponse.class,
            ModelInstaller.ModelCatalogEntry.class,
            ModelInstaller.ModelFileSpec.class,
            ModelInstaller.ModelStatus.class
        })
public class AppReflectionConfig {}
