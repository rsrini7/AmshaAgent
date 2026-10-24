package com.amsha.persistence.sqlite;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.amsha.common.util.LogUtil;
import com.amsha.persistence.api.EmbeddingIndexDescriptor;
import com.amsha.persistence.api.PersistenceBackend;
import com.amsha.persistence.api.VectorIndexCompatibility;
import com.amsha.persistence.api.VectorIndexMetadataPort;
import com.amsha.persistence.api.VectorIndexNamespace;
import com.amsha.persistence.sqlite.jdbi.VectorIndexMetadataJdbiDao;

import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

/** SQLite persistence and compatibility gate for durable vector namespaces. */
@ApplicationScoped
@Unremovable
@IfBuildProperty(name = PersistenceBackend.PROPERTY, stringValue = PersistenceBackend.SQLITE, enableIfMissing = true)
public class SqliteVectorIndexMetadataAdapter implements VectorIndexMetadataPort {

    private static final Logger log = Logger.getLogger(SqliteVectorIndexMetadataAdapter.class);

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
                    "SQLite vector namespace [%s] metadata unavailable; vector search disabled: %s",
                    namespace.persistentId(), LogUtil.rootMessage(e));
            return result;
        }
    }

    @Override
    public VectorIndexCompatibility markUnavailable(VectorIndexNamespace namespace) {
        VectorIndexCompatibility result = VectorIndexCompatibility.unavailable(namespace);
        compatibility.put(namespace, result);
        return result;
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
        return jdbi.withExtension(VectorIndexMetadataJdbiDao.class, dao -> dao.find(namespace.persistentId()));
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
        String now = Instant.now().toString();
        jdbi.useExtension(
                VectorIndexMetadataJdbiDao.class, dao -> dao.upsert(namespace.persistentId(), descriptor, now, now));
    }

    private static void logResult(VectorIndexCompatibility result) {
        if (result.vectorEnabled()) {
            log.infof(
                    "SQLite vector namespace [%s] descriptor %s (%s)",
                    result.namespace().persistentId(), result.activeFingerprint(), result.status());
            return;
        }
        log.warnf(
                "SQLite vector namespace [%s] descriptor %s; vector search disabled. "
                        + "Development reset: stop Amsha and delete the configured development data directory.",
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
