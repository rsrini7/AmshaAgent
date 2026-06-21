package com.amsha.persistence.postgres;

import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.AuditEventDto;
import com.amsha.persistence.api.ChatMemoryDto;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CompactionSummaryDto;
import com.amsha.persistence.api.CronRunDto;
import com.amsha.persistence.api.MemoryChunkDto;
import com.amsha.persistence.api.PromptDto;
import com.amsha.persistence.api.ReasoningStepDto;
import com.amsha.persistence.api.SecretDto;
import com.amsha.persistence.api.SkillDto;
import com.amsha.persistence.postgres.jdbi.ArchivalMemoryJdbiDao;
import com.amsha.persistence.postgres.jdbi.AuditJdbiDao;
import com.amsha.persistence.postgres.jdbi.ChatMemoryJdbiDao;
import com.amsha.persistence.postgres.jdbi.CheckpointJdbiDao;
import com.amsha.persistence.postgres.jdbi.CompactionJdbiDao;
import com.amsha.persistence.postgres.jdbi.CronJdbiDao;
import com.amsha.persistence.postgres.jdbi.CronRunJdbiDao;
import com.amsha.persistence.postgres.jdbi.JdbiProducer;
import com.amsha.persistence.postgres.jdbi.LockJdbiDao;
import com.amsha.persistence.postgres.jdbi.PromptJdbiDao;
import com.amsha.persistence.postgres.jdbi.RagChunkJdbiDao;
import com.amsha.persistence.postgres.jdbi.RecallMemoryJdbiDao;
import com.amsha.persistence.postgres.jdbi.SecretJdbiDao;
import com.amsha.persistence.postgres.jdbi.SkillJdbiDao;

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
            ReasoningStepDto.class,
            CronRunDto.class,
            PromptDto.class,
            MemoryChunkDto.class,
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
            RagChunkJdbiDao.class,
            ArchivalMemoryJdbiDao.class,
            // JDBI infrastructure
            JdbiProducer.class,
            // Store adapters (CDI beans)
            PostgresPersistenceAdapter.class,
            PostgresAuditEventStoreAdapter.class,
            PostgresAuditSchemaEnforcer.class,
            PostgresChatMemoryStoreAdapter.class,
            PostgresCheckpointStoreAdapter.class,
            PostgresCompactionStoreAdapter.class,
            PostgresCronStoreAdapter.class,
            PostgresCronRunStoreAdapter.class,
            PostgresMemoryRetrievalAdapter.class,
            PostgresArchivalMemoryStore.class,
            PostgresPromptStoreAdapter.class,
            PostgresSecretStoreAdapter.class,
            PostgresSkillStoreAdapter.class,
            PostgresAdvisoryLockAdapter.class,
            PostgresRecallMemoryStore.class,
            RecallMemoryJdbiDao.class
        })
public class PostgresPersistenceReflectionConfig {}
