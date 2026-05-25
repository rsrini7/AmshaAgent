package com.arcclaw.persistence.sqlite;

import com.arcclaw.persistence.api.AgentCronDto;
import com.arcclaw.persistence.api.AuditEventDto;
import com.arcclaw.persistence.api.ChatMemoryDto;
import com.arcclaw.persistence.api.CheckpointDto;
import com.arcclaw.persistence.api.CompactionSummaryDto;
import com.arcclaw.persistence.api.CronRunDto;
import com.arcclaw.persistence.api.PromptDto;
import com.arcclaw.persistence.api.ReasoningStepDto;
import com.arcclaw.persistence.api.SecretDto;
import com.arcclaw.persistence.api.SkillDto;
import com.arcclaw.persistence.common.SessionKnowledgeBase;
import com.arcclaw.persistence.sqlite.jdbi.AuditJdbiDao;
import com.arcclaw.persistence.sqlite.jdbi.ChatMemoryJdbiDao;
import com.arcclaw.persistence.sqlite.jdbi.CheckpointJdbiDao;
import com.arcclaw.persistence.sqlite.jdbi.CompactionJdbiDao;
import com.arcclaw.persistence.sqlite.jdbi.CoreMemoryJdbiDao;
import com.arcclaw.persistence.sqlite.jdbi.CronJdbiDao;
import com.arcclaw.persistence.sqlite.jdbi.CronRunJdbiDao;
import com.arcclaw.persistence.sqlite.jdbi.JdbiProducer;
import com.arcclaw.persistence.sqlite.jdbi.LockJdbiDao;
import com.arcclaw.persistence.sqlite.jdbi.PromptJdbiDao;
import com.arcclaw.persistence.sqlite.jdbi.RecallMemoryJdbiDao;
import com.arcclaw.persistence.sqlite.jdbi.SecretJdbiDao;
import com.arcclaw.persistence.sqlite.jdbi.SkillJdbiDao;
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
        FtsConnectionInterceptor.class
    }
)
public class SqlitePersistenceReflectionConfig {
}
