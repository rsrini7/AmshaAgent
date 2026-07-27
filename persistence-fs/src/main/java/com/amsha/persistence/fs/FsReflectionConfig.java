package com.amsha.persistence.fs;

import com.amsha.persistence.api.ApiCredentialDto;
import com.amsha.persistence.api.BootstrapStateDto;
import com.amsha.persistence.api.ExternalIdentityDto;
import com.amsha.persistence.api.IdentityInvitationDto;
import com.amsha.persistence.api.IdentityUserDto;
import com.amsha.persistence.api.IdentityWorkspaceDto;
import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.persistence.api.LlmConnectionSecretRefDto;
import com.amsha.persistence.api.McpConnectionCleanupFailureDto;
import com.amsha.persistence.api.McpConnectionDto;
import com.amsha.persistence.api.McpConnectionHeaderSecretRefDto;
import com.amsha.persistence.api.ModelProfileDto;
import com.amsha.persistence.api.ModelRouteAssignmentDto;
import com.amsha.persistence.api.OnboardingCommitReceiptDto;
import com.amsha.persistence.api.OnboardingDraftDto;
import com.amsha.persistence.api.OnboardingDraftPurpose;
import com.amsha.persistence.api.OnboardingDraftState;
import com.amsha.persistence.api.OnboardingExtensionStateDto;
import com.amsha.persistence.api.WebSessionDto;
import com.amsha.persistence.api.WorkspaceConnectionGrantDto;
import com.amsha.persistence.api.WorkspaceMembershipDto;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(
        targets = {
            FsSkillStoreAdapter.class,
            FsChatMemoryStoreAdapter.class,
            FsSecretStoreAdapter.class,
            FsCompactionStoreAdapter.class,
            FsAuditEventStoreAdapter.class,
            FsAuditSchemaEnforcer.class,
            FsCronStoreAdapter.class,
            FsCronRunStoreAdapter.class,
            FsCheckpointStoreAdapter.class,
            FsCheckpointHelper.class,
            FsMemoryRetrievalAdapter.class,
            FsPersistenceAdapter.class,
            FsPromptStoreAdapter.class,
            FsMcpConnectionStoreAdapter.class,
            FsLockManager.class,
            FilesystemFileStore.class,
            FsEvolutionEventStore.class,
            FsExtractedMemoryStore.class,
            FsCoreMemoryStore.class,
            FsRecallMemoryStore.class,
            FsArchivalMemoryStore.class,
            FsIdentityStoreAdapter.class,
            FsApiCredentialStoreAdapter.class,
            FsWebSessionStoreAdapter.class,
            FsIdentityInvitationStoreAdapter.class,
            FsBootstrapStateStoreAdapter.class,
            FsOnboardingDraftStoreAdapter.class,
            FsOnboardingCommitReceiptStoreAdapter.class,
            FsOnboardingExtensionStateStoreAdapter.class,
            FsLlmProfileStoreAdapter.class,
            McpConnectionDto.class,
            McpConnectionHeaderSecretRefDto.class,
            McpConnectionCleanupFailureDto.class,
            IdentityUserDto.class,
            ExternalIdentityDto.class,
            IdentityWorkspaceDto.class,
            WorkspaceMembershipDto.class,
            ApiCredentialDto.class,
            WebSessionDto.class,
            IdentityInvitationDto.class,
            BootstrapStateDto.class,
            OnboardingDraftDto.class,
            OnboardingCommitReceiptDto.class,
            OnboardingDraftPurpose.class,
            OnboardingDraftState.class,
            OnboardingExtensionStateDto.class,
            LlmConnectionDto.class,
            LlmConnectionSecretRefDto.class,
            ModelProfileDto.class,
            ModelRouteAssignmentDto.class,
            WorkspaceConnectionGrantDto.class
        },
        classNames = {
            "org.apache.lucene.search.BoostAttribute",
            "org.apache.lucene.search.BoostAttributeImpl",
            "org.apache.lucene.search.MaxNonCompetitiveBoostAttribute",
            "org.apache.lucene.search.MaxNonCompetitiveBoostAttributeImpl"
        })
public class FsReflectionConfig {}
