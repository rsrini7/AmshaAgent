package com.arcclaw.persistence.sqlite;

import com.arcclaw.core.state.InstantToTextConverter;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(
    targets = {
        GlobalKnowledgeBase.class,
        SessionKnowledgeBase.class,
        RagSchemaInitializer.class,
        SqliteMemoryRetrievalAdapter.class,
        SqliteCheckpointStoreAdapter.class,
        InstantToTextConverter.class,
        SqliteWalConfigurer.class,
        ArcClawSQLiteDialect.class,
        FtsConnectionInterceptor.class
    }
)
public class SqlitePersistenceReflectionConfig {
}
