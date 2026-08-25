package com.amsha.persistence.fs;

import com.amsha.persistence.api.ApiCredentialDto;
import com.amsha.persistence.api.ArchivalChunkDto;
import com.amsha.persistence.api.BootstrapStateDto;
import com.amsha.persistence.api.CoreMemoryBlockDto;
import com.amsha.persistence.api.ExternalIdentityDto;
import com.amsha.persistence.api.IdentityInvitationDto;
import com.amsha.persistence.api.IdentityUserDto;
import com.amsha.persistence.api.IdentityWorkspaceDto;
import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.persistence.api.LlmConnectionSecretRefDto;
import com.amsha.persistence.api.McpConnectionCleanupFailureDto;
import com.amsha.persistence.api.McpConnectionDto;
import com.amsha.persistence.api.McpConnectionHeaderSecretRefDto;
import com.amsha.persistence.api.McpConnectionToolPolicyDto;
import com.amsha.persistence.api.ModelProfileDto;
import com.amsha.persistence.api.ModelRouteAssignmentDto;
import com.amsha.persistence.api.OnboardingCommitReceiptDto;
import com.amsha.persistence.api.OnboardingDraftDto;
import com.amsha.persistence.api.OnboardingDraftPurpose;
import com.amsha.persistence.api.OnboardingDraftState;
import com.amsha.persistence.api.OnboardingExtensionStateDto;
import com.amsha.persistence.api.RecallMessageDto;
import com.amsha.persistence.api.WebSessionDto;
import com.amsha.persistence.api.WorkspaceConnectionGrantDto;
import com.amsha.persistence.api.WorkspaceMembershipDto;
import com.amsha.persistence.fs.lucene.LuceneIndexManager;
import com.amsha.selfevolve.api.DirectiveState;
import com.amsha.selfevolve.api.EvolutionEvent;
import com.amsha.selfevolve.api.ExtractedMemory;
import com.amsha.selfevolve.api.MemoryRecordType;
import com.amsha.selfevolve.api.SynthesizedDirective;

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
            FsVectorIndexMetadataAdapter.class,
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
            McpConnectionToolPolicyDto.class,
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
            WorkspaceConnectionGrantDto.class,
            ArchivalChunkDto.class,
            RecallMessageDto.class,
            CoreMemoryBlockDto.class,
            EvolutionEvent.class,
            ExtractedMemory.class,
            SynthesizedDirective.class,
            DirectiveState.class,
            MemoryRecordType.class,
            LuceneIndexManager.ScoredResult.class
        },
        classNames = {
            "org.apache.lucene.search.BoostAttribute",
            "org.apache.lucene.search.BoostAttributeImpl",
            "org.apache.lucene.search.MaxNonCompetitiveBoostAttribute",
            "org.apache.lucene.search.MaxNonCompetitiveBoostAttributeImpl",
            "com.amsha.persistence.fs.FsVectorIndexMetadataAdapter$IndexDescriptorManifest",
            "com.amsha.persistence.fs.FsArchivalMemoryStore$ChunkList",
            "com.amsha.persistence.fs.FsRecallMemoryStore$MessageList",
            "com.amsha.persistence.fs.FsCoreMemoryStore$BlockMap",
            "com.amsha.persistence.fs.FsExtractedMemoryStore$MemoryBatch",
            "com.amsha.persistence.fs.FsDirectiveStore$DirectiveBatch",
            "com.amsha.persistence.fs.FsEvolutionEventStore$EventBatch",
            "com.amsha.persistence.fs.FsSecretStoreAdapter$SecretRecord",
            "com.amsha.persistence.fs.FsAuditEventStoreAdapter$IndexEntry",
            "com.amsha.persistence.fs.FsMemoryRetrievalAdapter$IndexEntry"
        })
public class FsReflectionConfig {}
