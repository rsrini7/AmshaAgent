package com.amsha.persistence.api;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * GraalVM native-image reflection registration for tiered memory DTOs and port interfaces.
 * <p>
 * Without explicit registration, Quarkus native-image builds strip these classes
 * because they are resolved via CDI proxies, JDBI constructor mappers, or
 * JSON serialization at runtime.
 */
@RegisterForReflection(
        targets = {
            EmbeddingIndexDescriptor.class,
            VectorIndexCompatibility.class,
            VectorIndexCompatibility.Status.class,
            VectorIndexNamespace.class,
            CoreMemoryBlockDto.class,
            RecallMessageDto.class,
            ArchivalChunkDto.class,
            TieredMemoryHealthDto.class,
            RecallSearchQuery.class,
            ArchivalSearchQuery.class,
            CheckpointDtoHelper.class,
            CoreMemoryStorePort.class,
            RecallMemoryStorePort.class,
            ArchivalMemoryStorePort.class,
            MemoryRetrievalPort.class,
            MemoryEntryDto.class,
            MemoryChunkDto.class,
            IngestResult.class,
            BackendCapabilities.class,
            VersionConflictException.class,
            ExecutionTraceDto.class,
            ExecutionRunSummaryDto.class,
            ExecutionTraceStorePort.class
        })
public class TieredMemoryReflectionConfig {}
