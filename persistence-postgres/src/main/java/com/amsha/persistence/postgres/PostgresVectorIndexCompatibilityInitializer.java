package com.amsha.persistence.postgres;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;

import com.amsha.common.util.LogUtil;
import com.amsha.persistence.api.EmbeddingIndexDescriptor;
import com.amsha.persistence.api.EmbeddingIndexDescriptorSource;
import com.amsha.persistence.api.PersistenceBackend;
import com.amsha.persistence.api.VectorIndexMetadataPort;
import com.amsha.persistence.api.VectorIndexNamespace;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.runtime.StartupEvent;

/** Validates PostgreSQL pgvector namespaces after Flyway has initialized their tables. */
@ApplicationScoped
@Unremovable
@IfBuildProperty(name = PersistenceBackend.PROPERTY, stringValue = PersistenceBackend.POSTGRES)
public class PostgresVectorIndexCompatibilityInitializer {

    private static final Logger log = Logger.getLogger(PostgresVectorIndexCompatibilityInitializer.class);

    @Inject
    Jdbi jdbi;

    @Inject
    VectorIndexMetadataPort metadata;

    @Inject
    Instance<EmbeddingIndexDescriptorSource> descriptorSources;

    @ConfigProperty(name = "amsha.rag.embed-dimensions", defaultValue = "768")
    int configuredDimension;

    void onStart(@Observes @Priority(Interceptor.Priority.APPLICATION + 300) StartupEvent event) {
        if (descriptorSources.isUnsatisfied() || descriptorSources.isAmbiguous()) {
            log.warn("PostgreSQL vector descriptor source unavailable or ambiguous; vector search remains disabled");
            return;
        }
        EmbeddingIndexDescriptor descriptor =
                descriptorSources.get().activeDescriptor().orElse(null);
        if (descriptor == null) {
            log.warn("PostgreSQL active embedding descriptor unavailable; vector search remains disabled");
            return;
        }
        if (descriptor.vectorDimension() != configuredDimension) {
            log.warnf(
                    "PostgreSQL vector dimension mismatch: descriptor=%d configured=%d; vector search disabled",
                    descriptor.vectorDimension(), configuredDimension);
            metadata.markUnavailable(VectorIndexNamespace.RAG_GLOBAL, descriptor);
            return;
        }
        verify(VectorIndexNamespace.RAG_GLOBAL, "SELECT count(*) FROM rag_chunk_embedding", descriptor);
    }

    private void verify(VectorIndexNamespace namespace, String countSql, EmbeddingIndexDescriptor descriptor) {
        try {
            long count = jdbi.withHandle(
                    handle -> handle.createQuery(countSql).mapTo(Long.class).one());
            metadata.verifyOrInitialize(namespace, descriptor, count == 0);
        } catch (RuntimeException e) {
            metadata.markUnavailable(namespace, descriptor);
            log.warnf(
                    "PostgreSQL vector namespace [%s] could not be inspected; vector search disabled: %s",
                    namespace.persistentId(), LogUtil.rootMessage(e));
        }
    }
}
