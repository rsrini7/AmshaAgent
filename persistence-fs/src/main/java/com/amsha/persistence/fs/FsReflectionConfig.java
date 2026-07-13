package com.amsha.persistence.fs;

import com.amsha.persistence.api.McpConnectionCleanupFailureDto;
import com.amsha.persistence.api.McpConnectionDto;
import com.amsha.persistence.api.McpConnectionHeaderSecretRefDto;

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
            FsCronRunStoreAdapter.class,
            FsCheckpointStoreAdapter.class,
            FsCheckpointHelper.class,
            FsMemoryRetrievalAdapter.class,
            FsPersistenceAdapter.class,
            FsPromptStoreAdapter.class,
            FsMcpConnectionStoreAdapter.class,
            FsLockManager.class,
            FilesystemFileStore.class,
            FsEvolutionEventStore.class,
            FsExtractedMemoryStore.class,
            FsCoreMemoryStore.class,
            FsRecallMemoryStore.class,
            FsArchivalMemoryStore.class,
            McpConnectionDto.class,
            McpConnectionHeaderSecretRefDto.class,
            McpConnectionCleanupFailureDto.class
        },
        classNames = {
            "org.apache.lucene.search.BoostAttribute",
            "org.apache.lucene.search.BoostAttributeImpl",
            "org.apache.lucene.search.MaxNonCompetitiveBoostAttribute",
            "org.apache.lucene.search.MaxNonCompetitiveBoostAttributeImpl"
        })
public class FsReflectionConfig {}
