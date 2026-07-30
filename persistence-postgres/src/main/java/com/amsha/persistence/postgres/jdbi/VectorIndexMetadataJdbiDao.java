package com.amsha.persistence.postgres.jdbi;

import java.time.Instant;
import java.util.Optional;

import com.amsha.persistence.api.EmbeddingIndexDescriptor;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

@RegisterConstructorMapper(EmbeddingIndexDescriptor.class)
public interface VectorIndexMetadataJdbiDao {

    @SqlQuery("SELECT schema_version AS \"schemaVersion\", provider_id AS \"providerId\", space_id AS \"spaceId\", "
            + "model_revision AS \"modelRevision\", tokenizer_revision AS \"tokenizerRevision\", "
            + "preprocessing_revision AS \"preprocessingRevision\", pooling_strategy AS \"poolingStrategy\", "
            + "l2_normalize AS \"l2Normalize\", vector_dimension AS \"vectorDimension\", "
            + "numeric_precision AS \"numericPrecision\", chunking_strategy AS \"chunkingStrategy\", "
            + "chunking_revision AS \"chunkingRevision\", max_window_tokens AS \"maxWindowTokens\", "
            + "window_overlap_tokens AS \"windowOverlapTokens\", "
            + "multi_window_aggregation AS \"multiWindowAggregation\", "
            + "descriptor_fingerprint AS fingerprint "
            + "FROM vector_index_metadata WHERE namespace = :namespace")
    Optional<EmbeddingIndexDescriptor> find(@Bind("namespace") String namespace);

    @SqlUpdate("INSERT INTO vector_index_metadata (namespace, schema_version, provider_id, space_id, "
            + "model_revision, tokenizer_revision, preprocessing_revision, pooling_strategy, "
            + "l2_normalize, vector_dimension, numeric_precision, chunking_strategy, "
            + "chunking_revision, max_window_tokens, window_overlap_tokens, "
            + "multi_window_aggregation, descriptor_fingerprint, created_at, updated_at) "
            + "VALUES (:namespace, :schemaVersion, :providerId, :spaceId, :modelRevision, "
            + ":tokenizerRevision, :preprocessingRevision, :poolingStrategy, :l2Normalize, "
            + ":vectorDimension, :numericPrecision, :chunkingStrategy, :chunkingRevision, "
            + ":maxWindowTokens, :windowOverlapTokens, :multiWindowAggregation, :fingerprint, "
            + ":createdAt, :updatedAt) ON CONFLICT(namespace) DO UPDATE SET "
            + "schema_version = excluded.schema_version, provider_id = excluded.provider_id, "
            + "space_id = excluded.space_id, model_revision = excluded.model_revision, "
            + "tokenizer_revision = excluded.tokenizer_revision, "
            + "preprocessing_revision = excluded.preprocessing_revision, "
            + "pooling_strategy = excluded.pooling_strategy, l2_normalize = excluded.l2_normalize, "
            + "vector_dimension = excluded.vector_dimension, numeric_precision = excluded.numeric_precision, "
            + "chunking_strategy = excluded.chunking_strategy, chunking_revision = excluded.chunking_revision, "
            + "max_window_tokens = excluded.max_window_tokens, "
            + "window_overlap_tokens = excluded.window_overlap_tokens, "
            + "multi_window_aggregation = excluded.multi_window_aggregation, "
            + "descriptor_fingerprint = excluded.descriptor_fingerprint, updated_at = excluded.updated_at")
    void upsert(
            @Bind("namespace") String namespace,
            @BindMethods EmbeddingIndexDescriptor descriptor,
            @Bind("createdAt") Instant createdAt,
            @Bind("updatedAt") Instant updatedAt);
}
