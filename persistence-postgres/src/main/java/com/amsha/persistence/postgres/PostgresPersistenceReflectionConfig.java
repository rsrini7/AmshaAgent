package com.amsha.persistence.postgres;

import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.ApiCredentialDto;
import com.amsha.persistence.api.AuditEventDto;
import com.amsha.persistence.api.BootstrapStateDto;
import com.amsha.persistence.api.ChatMemoryDto;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CompactionSummaryDto;
import com.amsha.persistence.api.CoreMemoryBlockDto;
import com.amsha.persistence.api.CronRunDto;
import com.amsha.persistence.api.ExternalIdentityDto;
import com.amsha.persistence.api.IdentityInvitationDto;
import com.amsha.persistence.api.IdentityUserDto;
import com.amsha.persistence.api.IdentityWorkspaceDto;
import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.persistence.api.LlmConnectionSecretRefDto;
import com.amsha.persistence.api.McpConnectionCleanupFailureDto;
import com.amsha.persistence.api.McpConnectionDto;
import com.amsha.persistence.api.McpConnectionHeaderSecretRefDto;
import com.amsha.persistence.api.MemoryChunkDto;
import com.amsha.persistence.api.ModelProfileDto;
import com.amsha.persistence.api.ModelRouteAssignmentDto;
import com.amsha.persistence.api.OnboardingCommitReceiptDto;
import com.amsha.persistence.api.OnboardingDraftDto;
import com.amsha.persistence.api.OnboardingDraftPurpose;
import com.amsha.persistence.api.OnboardingDraftState;
import com.amsha.persistence.api.OnboardingExtensionStateDto;
import com.amsha.persistence.api.PromptDto;
import com.amsha.persistence.api.ReasoningStepDto;
import com.amsha.persistence.api.SecretDto;
import com.amsha.persistence.api.SkillDto;
import com.amsha.persistence.api.WebSessionDto;
import com.amsha.persistence.api.WorkspaceConnectionGrantDto;
import com.amsha.persistence.api.WorkspaceMembershipDto;
import com.amsha.persistence.postgres.jdbi.ApiCredentialJdbiDao;
import com.amsha.persistence.postgres.jdbi.ArchivalMemoryJdbiDao;
import com.amsha.persistence.postgres.jdbi.AuditJdbiDao;
import com.amsha.persistence.postgres.jdbi.BootstrapStateJdbiDao;
import com.amsha.persistence.postgres.jdbi.ChatMemoryJdbiDao;
import com.amsha.persistence.postgres.jdbi.CheckpointJdbiDao;
import com.amsha.persistence.postgres.jdbi.CompactionJdbiDao;
import com.amsha.persistence.postgres.jdbi.CoreMemoryJdbiDao;
import com.amsha.persistence.postgres.jdbi.CronJdbiDao;
import com.amsha.persistence.postgres.jdbi.CronRunJdbiDao;
import com.amsha.persistence.postgres.jdbi.IdentityInvitationJdbiDao;
import com.amsha.persistence.postgres.jdbi.IdentityJdbiDao;
import com.amsha.persistence.postgres.jdbi.JdbiProducer;
import com.amsha.persistence.postgres.jdbi.LlmProfilePostgresDao;
import com.amsha.persistence.postgres.jdbi.LockJdbiDao;
import com.amsha.persistence.postgres.jdbi.McpConnectionJdbiDao;
import com.amsha.persistence.postgres.jdbi.OnboardingCommitReceiptPostgresDao;
import com.amsha.persistence.postgres.jdbi.OnboardingDraftJdbiDao;
import com.amsha.persistence.postgres.jdbi.OnboardingExtensionStateJdbiDao;
import com.amsha.persistence.postgres.jdbi.PromptJdbiDao;
import com.amsha.persistence.postgres.jdbi.RagChunkJdbiDao;
import com.amsha.persistence.postgres.jdbi.RecallMemoryJdbiDao;
import com.amsha.persistence.postgres.jdbi.SecretJdbiDao;
import com.amsha.persistence.postgres.jdbi.SkillJdbiDao;
import com.amsha.persistence.postgres.jdbi.WebSessionJdbiDao;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(
        targets = {
            // JDBI DTOs (used by @RegisterConstructorMapper)
            CheckpointDto.class,
            AgentCronDto.class,
            AuditEventDto.class,
            ChatMemoryDto.class,
            SecretDto.class,
            SkillDto.class,
            CompactionSummaryDto.class,
            CoreMemoryBlockDto.class,
            ReasoningStepDto.class,
            CronRunDto.class,
            PromptDto.class,
            MemoryChunkDto.class,
            McpConnectionCleanupFailureDto.class,
            McpConnectionDto.class,
            McpConnectionHeaderSecretRefDto.class,
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
            // JDBI DAOs (SQL Object proxies)
            CheckpointJdbiDao.class,
            CronJdbiDao.class,
            CronRunJdbiDao.class,
            LockJdbiDao.class,
            SecretJdbiDao.class,
            SecretJdbiDao.SecretDtoRow.class,
            CompactionJdbiDao.class,
            CoreMemoryJdbiDao.class,
            AuditJdbiDao.class,
            ChatMemoryJdbiDao.class,
            SkillJdbiDao.class,
            PromptJdbiDao.class,
            RagChunkJdbiDao.class,
            ArchivalMemoryJdbiDao.class,
            McpConnectionJdbiDao.class,
            IdentityJdbiDao.class,
            ApiCredentialJdbiDao.class,
            WebSessionJdbiDao.class,
            IdentityInvitationJdbiDao.class,
            BootstrapStateJdbiDao.class,
            OnboardingDraftJdbiDao.class,
            OnboardingDraftJdbiDao.DraftRowMapper.class,
            OnboardingCommitReceiptPostgresDao.class,
            OnboardingExtensionStateJdbiDao.class,
            LlmProfilePostgresDao.class,
            // JDBI infrastructure
            JdbiProducer.class,
            // Store adapters (CDI beans)
            PostgresPersistenceAdapter.class,
            PostgresAuditEventStoreAdapter.class,
            PostgresAuditSchemaEnforcer.class,
            PostgresChatMemoryStoreAdapter.class,
            PostgresCoreMemoryStore.class,
            PostgresCheckpointStoreAdapter.class,
            PostgresCompactionStoreAdapter.class,
            PostgresCronStoreAdapter.class,
            PostgresCronRunStoreAdapter.class,
            PostgresMemoryRetrievalAdapter.class,
            PostgresArchivalMemoryStore.class,
            PostgresPromptStoreAdapter.class,
            PostgresSecretStoreAdapter.class,
            PostgresSkillStoreAdapter.class,
            PostgresMcpConnectionStoreAdapter.class,
            PostgresAdvisoryLockAdapter.class,
            PostgresRecallMemoryStore.class,
            RecallMemoryJdbiDao.class,
            PostgresIdentityStoreAdapter.class,
            PostgresApiCredentialStoreAdapter.class,
            PostgresWebSessionStoreAdapter.class,
            PostgresIdentityInvitationStoreAdapter.class,
            PostgresBootstrapStateStoreAdapter.class,
            PostgresOnboardingDraftStoreAdapter.class,
            PostgresOnboardingCommitReceiptStoreAdapter.class,
            PostgresOnboardingExtensionStateStoreAdapter.class,
            PostgresLlmProfileStoreAdapter.class
        })
public class PostgresPersistenceReflectionConfig {}
