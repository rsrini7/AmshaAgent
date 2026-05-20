package com.arcclaw.persistence.sqlite;

import com.arcclaw.persistence.common.SessionKnowledgeBase;
import com.arcclaw.persistence.common.entity.AdvisoryLockEntity;
import com.arcclaw.persistence.common.entity.AgentCheckpointEntity;
import com.arcclaw.persistence.common.entity.AgentCronEntity;
import com.arcclaw.persistence.common.entity.AgentSkillEntity;
import com.arcclaw.persistence.common.entity.AuditEventEntity;
import com.arcclaw.persistence.common.entity.ChatMemoryEntity;
import com.arcclaw.persistence.common.entity.CompactionSummaryEntity;
import com.arcclaw.persistence.common.entity.CronStatusConverter;
import com.arcclaw.persistence.common.entity.EncryptedSecretEntity;
import com.arcclaw.persistence.common.entity.InstantToTextConverter;
import com.arcclaw.persistence.common.entity.RagChunkEntity;
import com.arcclaw.persistence.common.entity.ReasoningStepEntity;
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

