package com.amsha.persistence.sqlite;

import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.AuditEventDto;
import com.amsha.persistence.api.ChatMemoryDto;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CompactionSummaryDto;
import com.amsha.persistence.api.CronRunDto;
import com.amsha.persistence.api.PromptDto;
import com.amsha.persistence.api.ReasoningStepDto;
import com.amsha.persistence.api.SecretDto;
import com.amsha.persistence.api.SkillDto;
import com.amsha.persistence.common.SessionKnowledgeBase;
import com.amsha.persistence.sqlite.jdbi.ArchivalMemoryJdbiDao;
import com.amsha.persistence.sqlite.jdbi.AuditJdbiDao;
import com.amsha.persistence.sqlite.jdbi.ChatMemoryJdbiDao;
import com.amsha.persistence.sqlite.jdbi.CheckpointJdbiDao;
import com.amsha.persistence.sqlite.jdbi.CompactionJdbiDao;
import com.amsha.persistence.sqlite.jdbi.CoreMemoryJdbiDao;
import com.amsha.persistence.sqlite.jdbi.CronJdbiDao;
import com.amsha.persistence.sqlite.jdbi.CronRunJdbiDao;
import com.amsha.persistence.sqlite.jdbi.JdbiProducer;
import com.amsha.persistence.sqlite.jdbi.LockJdbiDao;
import com.amsha.persistence.sqlite.jdbi.PromptJdbiDao;
import com.amsha.persistence.sqlite.jdbi.RecallMemoryJdbiDao;
import com.amsha.persistence.sqlite.jdbi.SecretJdbiDao;
import com.amsha.persistence.sqlite.jdbi.SkillJdbiDao;

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
            SqliteCronStoreAdapter.class,
            SqliteCronRunStoreAdapter.class,
            SqlitePromptStoreAdapter.class,
            SqliteSecretStoreAdapter.class,
            SqliteSkillStoreAdapter.class,
            SqliteAdvisoryLockAdapter.class,
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
