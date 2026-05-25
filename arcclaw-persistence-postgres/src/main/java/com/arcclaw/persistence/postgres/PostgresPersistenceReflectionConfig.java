package com.arcclaw.persistence.postgres;

import com.arcclaw.persistence.api.AgentCronDto;
import com.arcclaw.persistence.api.AuditEventDto;
import com.arcclaw.persistence.api.ChatMemoryDto;
import com.arcclaw.persistence.api.CheckpointDto;
import com.arcclaw.persistence.api.CompactionSummaryDto;
import com.arcclaw.persistence.api.CronRunDto;
import com.arcclaw.persistence.api.MemoryChunkDto;
import com.arcclaw.persistence.api.PromptDto;
import com.arcclaw.persistence.api.ReasoningStepDto;
import com.arcclaw.persistence.api.SecretDto;
import com.arcclaw.persistence.api.SkillDto;
import com.arcclaw.persistence.postgres.jdbi.ArchivalMemoryJdbiDao;
import com.arcclaw.persistence.postgres.jdbi.AuditJdbiDao;
import com.arcclaw.persistence.postgres.jdbi.ChatMemoryJdbiDao;
import com.arcclaw.persistence.postgres.jdbi.CheckpointJdbiDao;
import com.arcclaw.persistence.postgres.jdbi.CompactionJdbiDao;
import com.arcclaw.persistence.postgres.jdbi.CronJdbiDao;
import com.arcclaw.persistence.postgres.jdbi.CronRunJdbiDao;
import com.arcclaw.persistence.postgres.jdbi.JdbiProducer;
import com.arcclaw.persistence.postgres.jdbi.LockJdbiDao;
import com.arcclaw.persistence.postgres.jdbi.PromptJdbiDao;
import com.arcclaw.persistence.postgres.jdbi.RagChunkJdbiDao;
import com.arcclaw.persistence.postgres.jdbi.SecretJdbiDao;
import com.arcclaw.persistence.postgres.jdbi.SkillJdbiDao;
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
        PostgresRecallMemoryStore.class
    }
)
public class PostgresPersistenceReflectionConfig {
}
