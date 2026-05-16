package com.arcclaw.persistence.fs;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(
    targets = {
        FsSkillStoreAdapter.class,
        FsChatMemoryStoreAdapter.class,
        FsSecretStoreAdapter.class,
        FsCompactionStoreAdapter.class,
        FsAuditEventStoreAdapter.class,
        FsAuditSchemaEnforcer.class,
        FsCronStoreAdapter.class,
        FsCheckpointStoreAdapter.class,
        FsMemoryRetrievalAdapter.class,
        FsPersistenceAdapter.class,
        FsLockManager.class,
        FilesystemFileStore.class
    }
)
public class FsReflectionConfig {
}
