package com.arcclaw.persistence.sqlite;

import com.arcclaw.persistence.sqlite.entity.AdvisoryLockEntity;
import com.arcclaw.persistence.sqlite.entity.AgentCheckpointEntity;
import com.arcclaw.persistence.sqlite.entity.AgentCronEntity;
import com.arcclaw.persistence.sqlite.entity.AgentSkillEntity;
import com.arcclaw.persistence.sqlite.entity.AuditEventEntity;
import com.arcclaw.persistence.sqlite.entity.ChatMemoryEntity;
import com.arcclaw.persistence.sqlite.entity.CompactionSummaryEntity;
import com.arcclaw.persistence.sqlite.entity.CronStatusConverter;
import com.arcclaw.persistence.sqlite.entity.EncryptedSecretEntity;
import com.arcclaw.persistence.sqlite.entity.InstantToTextConverter;
import com.arcclaw.persistence.sqlite.entity.RagChunkEntity;
import com.arcclaw.persistence.sqlite.entity.ReasoningStepEntity;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(
    targets = {
        AdvisoryLockEntity.class,
        AgentCheckpointEntity.class,
        AgentCronEntity.class,
        AgentSkillEntity.class,
        AuditEventEntity.class,
        ChatMemoryEntity.class,
        CompactionSummaryEntity.class,
        CronStatusConverter.class,
        EncryptedSecretEntity.class,
        InstantToTextConverter.class,
        RagChunkEntity.class,
        ReasoningStepEntity.class,
        GlobalKnowledgeBase.class,
        SessionKnowledgeBase.class,
        RagSchemaInitializer.class,
        SqliteMemoryRetrievalAdapter.class,
        SqliteCheckpointStoreAdapter.class,
        SqliteWalConfigurer.class,
        ArcClawSQLiteDialect.class,
        FtsConnectionInterceptor.class
    }
)
public class SqlitePersistenceReflectionConfig {
}

