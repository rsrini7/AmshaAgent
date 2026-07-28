package com.amsha.persistence.postgres;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.amsha.common.util.LogUtil;
import com.amsha.persistence.api.EmbeddingIndexDescriptor;
import com.amsha.persistence.api.VectorIndexCompatibility;
import com.amsha.persistence.api.VectorIndexMetadataPort;
import com.amsha.persistence.api.VectorIndexNamespace;

import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

/** PostgreSQL persistence and compatibility gate for durable vector namespaces. */
@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "postgres")
public class PostgresVectorIndexMetadataAdapter implements VectorIndexMetadataPort {

    private static final Logger log = Logger.getLogger(PostgresVectorIndexMetadataAdapter.class);

    @Inject
    Jdbi jdbi;

    private final Map<VectorIndexNamespace, VectorIndexCompatibility> compatibility = new ConcurrentHashMap<>();

    @Override
    public VectorIndexCompatibility verifyOrInitialize(
            VectorIndexNamespace namespace, EmbeddingIndexDescriptor activeDescriptor, boolean indexEmpty) {
        try {
            Optional<EmbeddingIndexDescriptor> stored = find(namespace);
            VectorIndexCompatibility result =
                    VectorIndexCompatibility.assess(namespace, activeDescriptor, stored, indexEmpty);
            if (result.status() == VectorIndexCompatibility.Status.INITIALIZED) {
                upsert(namespace, activeDescriptor);
            }
            compatibility.put(namespace, result);
            logResult(result);
            return result;
        } catch (RuntimeException e) {
            VectorIndexCompatibility result = unavailable(namespace, activeDescriptor);
            compatibility.put(namespace, result);
            log.warnf(
                    "PostgreSQL vector namespace [%s] metadata unavailable; vector search disabled: %s",
                    namespace.persistentId(), LogUtil.rootMessage(e));
            return result;
        }
    }

    @Override
    public VectorIndexCompatibility markUnavailable(
            VectorIndexNamespace namespace, EmbeddingIndexDescriptor activeDescriptor) {
        VectorIndexCompatibility result = unavailable(namespace, activeDescriptor);
        compatibility.put(namespace, result);
        return result;
    }

    @Override
    public Optional<EmbeddingIndexDescriptor> find(VectorIndexNamespace namespace) {
        return jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT schema_version, provider_id, space_id, model_revision, tokenizer_revision, "
                                + "preprocessing_revision, pooling_strategy, l2_normalize, vector_dimension, "
                                + "numeric_precision, chunking_strategy, chunking_revision, max_window_tokens, "
                                + "window_overlap_tokens, multi_window_aggregation, descriptor_fingerprint "
                                + "FROM vector_index_metadata WHERE namespace = :namespace")
                .bind("namespace", namespace.persistentId())
                .map((rs, ctx) -> new EmbeddingIndexDescriptor(
                        rs.getInt("schema_version"),
                        rs.getString("provider_id"),
                        rs.getString("space_id"),
                        rs.getString("model_revision"),
                        rs.getString("tokenizer_revision"),
                        rs.getString("preprocessing_revision"),
                        rs.getString("pooling_strategy"),
                        rs.getBoolean("l2_normalize"),
                        rs.getInt("vector_dimension"),
                        rs.getString("numeric_precision"),
                        rs.getString("chunking_strategy"),
                        rs.getString("chunking_revision"),
                        rs.getInt("max_window_tokens"),
                        rs.getInt("window_overlap_tokens"),
                        rs.getString("multi_window_aggregation"),
                        rs.getString("descriptor_fingerprint")))
                .findOne());
    }

    @Override
    public boolean isVectorEnabled(VectorIndexNamespace namespace) {
        VectorIndexCompatibility result = compatibility.get(namespace);
        return result != null && result.vectorEnabled();
    }

    @Override
    public Map<VectorIndexNamespace, VectorIndexCompatibility> compatibilitySnapshot() {
        return Map.copyOf(compatibility);
    }

    private void upsert(VectorIndexNamespace namespace, EmbeddingIndexDescriptor descriptor) {
        Instant now = Instant.now();
        jdbi.useHandle(handle -> handle.createUpdate(
                        "INSERT INTO vector_index_metadata (namespace, schema_version, provider_id, space_id, "
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
                .bind("namespace", namespace.persistentId())
                .bind("schemaVersion", descriptor.schemaVersion())
                .bind("providerId", descriptor.providerId())
                .bind("spaceId", descriptor.spaceId())
                .bind("modelRevision", descriptor.modelRevision())
                .bind("tokenizerRevision", descriptor.tokenizerRevision())
                .bind("preprocessingRevision", descriptor.preprocessingRevision())
                .bind("poolingStrategy", descriptor.poolingStrategy())
                .bind("l2Normalize", descriptor.l2Normalize())
                .bind("vectorDimension", descriptor.vectorDimension())
                .bind("numericPrecision", descriptor.numericPrecision())
                .bind("chunkingStrategy", descriptor.chunkingStrategy())
                .bind("chunkingRevision", descriptor.chunkingRevision())
                .bind("maxWindowTokens", descriptor.maxWindowTokens())
                .bind("windowOverlapTokens", descriptor.windowOverlapTokens())
                .bind("multiWindowAggregation", descriptor.multiWindowAggregation())
                .bind("fingerprint", descriptor.fingerprint())
                .bind("createdAt", now)
                .bind("updatedAt", now)
                .execute());
    }

    private static void logResult(VectorIndexCompatibility result) {
        if (result.vectorEnabled()) {
            log.infof(
                    "PostgreSQL vector namespace [%s] descriptor %s (%s)",
                    result.namespace().persistentId(), result.activeFingerprint(), result.status());
            return;
        }
        log.warnf(
                "PostgreSQL vector namespace [%s] descriptor %s; vector search disabled. "
                        + "Development reset: recreate the configured development database or volume.",
                result.namespace().persistentId(), result.status());
    }

    private static VectorIndexCompatibility unavailable(
            VectorIndexNamespace namespace, EmbeddingIndexDescriptor activeDescriptor) {
        return new VectorIndexCompatibility(
                namespace,
                VectorIndexCompatibility.Status.UNAVAILABLE,
                activeDescriptor.fingerprint(),
                Optional.empty());
    }
}
