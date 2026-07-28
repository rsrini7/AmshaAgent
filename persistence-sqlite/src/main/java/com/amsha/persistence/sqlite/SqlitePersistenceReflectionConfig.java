package com.amsha.persistence.sqlite;

import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.ApiCredentialDto;
import com.amsha.persistence.api.AuditEventDto;
import com.amsha.persistence.api.BootstrapStateDto;
import com.amsha.persistence.api.ChatMemoryDto;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CompactionSummaryDto;
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
import com.amsha.persistence.common.SessionKnowledgeBase;
import com.amsha.persistence.sqlite.jdbi.ApiCredentialJdbiDao;
import com.amsha.persistence.sqlite.jdbi.ArchivalMemoryJdbiDao;
import com.amsha.persistence.sqlite.jdbi.AuditJdbiDao;
import com.amsha.persistence.sqlite.jdbi.BootstrapStateJdbiDao;
import com.amsha.persistence.sqlite.jdbi.ChatMemoryJdbiDao;
import com.amsha.persistence.sqlite.jdbi.CheckpointJdbiDao;
import com.amsha.persistence.sqlite.jdbi.CompactionJdbiDao;
import com.amsha.persistence.sqlite.jdbi.CoreMemoryJdbiDao;
import com.amsha.persistence.sqlite.jdbi.CronJdbiDao;
import com.amsha.persistence.sqlite.jdbi.CronRunJdbiDao;
import com.amsha.persistence.sqlite.jdbi.IdentityInvitationJdbiDao;
import com.amsha.persistence.sqlite.jdbi.IdentityJdbiDao;
import com.amsha.persistence.sqlite.jdbi.JdbiProducer;
import com.amsha.persistence.sqlite.jdbi.LlmProfileJdbiDao;
import com.amsha.persistence.sqlite.jdbi.LockJdbiDao;
import com.amsha.persistence.sqlite.jdbi.McpConnectionJdbiDao;
import com.amsha.persistence.sqlite.jdbi.OnboardingCommitReceiptJdbiDao;
import com.amsha.persistence.sqlite.jdbi.OnboardingDraftJdbiDao;
import com.amsha.persistence.sqlite.jdbi.OnboardingExtensionStateJdbiDao;
import com.amsha.persistence.sqlite.jdbi.PromptJdbiDao;
import com.amsha.persistence.sqlite.jdbi.RecallMemoryJdbiDao;
import com.amsha.persistence.sqlite.jdbi.SecretJdbiDao;
import com.amsha.persistence.sqlite.jdbi.SkillJdbiDao;
import com.amsha.persistence.sqlite.jdbi.WebSessionJdbiDao;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(
        targets = {
            // JDBI DTOs (replaced JPA entities)
            CheckpointDto.class,
            AgentCronDto.class,
            AuditEventDto.class,
            ChatMemoryDto.class,
            SecretDto.class,
            SkillDto.class,
            CompactionSummaryDto.class,
            ReasoningStepDto.class,
            CronRunDto.class,
            PromptDto.class,
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
            AuditJdbiDao.class,
            ChatMemoryJdbiDao.class,
            SkillJdbiDao.class,
            PromptJdbiDao.class,
            CoreMemoryJdbiDao.class,
            RecallMemoryJdbiDao.class,
            ArchivalMemoryJdbiDao.class,
            McpConnectionJdbiDao.class,
            IdentityJdbiDao.class,
            ApiCredentialJdbiDao.class,
            WebSessionJdbiDao.class,
            IdentityInvitationJdbiDao.class,
            BootstrapStateJdbiDao.class,
            OnboardingDraftJdbiDao.class,
            OnboardingCommitReceiptJdbiDao.class,
            OnboardingDraftJdbiDao.DraftRowMapper.class,
            OnboardingExtensionStateJdbiDao.class,
            LlmProfileJdbiDao.class,
            // JDBI infrastructure
            JdbiProducer.class,
            JdbiRetryWrapper.class,
            FtsSchemaInitializer.class,
            // Store adapters (CDI beans)
            SqlitePersistenceAdapter.class,
            SqliteAuditEventStoreAdapter.class,
            SqliteChatMemoryStoreAdapter.class,
            SqliteCheckpointStoreAdapter.class,
            SqliteCompactionStoreAdapter.class,
            SqliteCoreMemoryStore.class,
            SqliteRecallMemoryStore.class,
            SqliteArchivalMemoryStore.class,
            ArchivalVecSchemaInitializer.class,
            VectorIngestionService.class,
            SqliteVectorIndexMetadataAdapter.class,
            SqliteVectorIndexCompatibilityInitializer.class,
            SqliteCronStoreAdapter.class,
            SqliteCronRunStoreAdapter.class,
            SqlitePromptStoreAdapter.class,
            SqliteSecretStoreAdapter.class,
            SqliteSkillStoreAdapter.class,
            SqliteMcpConnectionStoreAdapter.class,
            SqliteAdvisoryLockAdapter.class,
            SqliteIdentityStoreAdapter.class,
            SqliteApiCredentialStoreAdapter.class,
            SqliteWebSessionStoreAdapter.class,
            SqliteIdentityInvitationStoreAdapter.class,
            SqliteBootstrapStateStoreAdapter.class,
            SqliteOnboardingDraftStoreAdapter.class,
            SqliteOnboardingCommitReceiptStoreAdapter.class,
            SqliteOnboardingExtensionStateStoreAdapter.class,
            SqliteLlmProfileStoreAdapter.class,
            // Existing infrastructure
            GlobalKnowledgeBase.class,
            SessionKnowledgeBase.class,
            RagSchemaInitializer.class,
            SqliteMemoryRetrievalAdapter.class,
            SqliteWalConfigurer.class,
            FtsConnectionInterceptor.class,
            FtsCleanCallback.class
        })
public class SqlitePersistenceReflectionConfig {}
