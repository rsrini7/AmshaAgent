package com.arcclaw.persistence.sqlite;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(
    targets = {
        GlobalKnowledgeBase.class,
        SessionKnowledgeBase.class,
        RagSchemaInitializer.class,
        SqliteMemoryRetrievalAdapter.class,
        SqliteCheckpointStoreAdapter.class
    }
)
public class SqlitePersistenceReflectionConfig {
}
