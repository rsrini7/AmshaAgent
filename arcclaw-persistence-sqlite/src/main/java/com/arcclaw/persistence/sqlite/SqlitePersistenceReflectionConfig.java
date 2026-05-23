package com.arcclaw.persistence.sqlite;

import com.arcclaw.persistence.api.AgentCronDto;
import com.arcclaw.persistence.api.AuditEventDto;
import com.arcclaw.persistence.api.ChatMemoryDto;
import com.arcclaw.persistence.api.CheckpointDto;
import com.arcclaw.persistence.api.CompactionSummaryDto;
import com.arcclaw.persistence.api.CronRunDto;
import com.arcclaw.persistence.api.ReasoningStepDto;
import com.arcclaw.persistence.api.SecretDto;
import com.arcclaw.persistence.api.SkillDto;
import com.arcclaw.persistence.common.SessionKnowledgeBase;
import com.arcclaw.persistence.sqlite.jdbi.CheckpointJdbiDao;
import com.arcclaw.persistence.sqlite.jdbi.CompactionJdbiDao;
import com.arcclaw.persistence.sqlite.jdbi.CronJdbiDao;
import com.arcclaw.persistence.sqlite.jdbi.CronRunJdbiDao;
import com.arcclaw.persistence.sqlite.jdbi.LockJdbiDao;
import com.arcclaw.persistence.sqlite.jdbi.SecretJdbiDao;
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
        // JDBI DAOs (SQL Object proxies)
        CheckpointJdbiDao.class,
        CronJdbiDao.class,
        CronRunJdbiDao.class,
        LockJdbiDao.class,
        SecretJdbiDao.SecretDtoRow.class,
        CompactionJdbiDao.class,
        // JDBI infrastructure
        JdbiRetryWrapper.class,
        SqlitePersistenceAdapter.class,
        // Existing infrastructure (keep)
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
