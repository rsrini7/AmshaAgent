package com.amsha.persistence.sqlite;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;

import com.amsha.common.util.LogUtil;
import com.amsha.persistence.api.EmbeddingIndexDescriptor;
import com.amsha.persistence.api.EmbeddingIndexDescriptorSource;
import com.amsha.persistence.api.VectorIndexMetadataPort;
import com.amsha.persistence.api.VectorIndexNamespace;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.runtime.StartupEvent;

/** Validates SQLite vec0 namespaces after their virtual tables have been initialized. */
@ApplicationScoped
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "sqlite", enableIfMissing = true)
public class SqliteVectorIndexCompatibilityInitializer {

    private static final Logger log = Logger.getLogger(SqliteVectorIndexCompatibilityInitializer.class);

    @Inject
    Jdbi jdbi;

    @Inject
    FtsConnectionInterceptor ftsInterceptor;

    @Inject
    VectorIndexMetadataPort metadata;

    @Inject
    Instance<EmbeddingIndexDescriptorSource> descriptorSources;

    @ConfigProperty(name = "amsha.rag.embed-dimensions", defaultValue = "768")
    int configuredDimension;

    void onStart(@Observes @Priority(Interceptor.Priority.APPLICATION + 300) StartupEvent event) {
        if (!ftsInterceptor.isVectorSearchEnabled()) return;
        if (descriptorSources.isUnsatisfied() || descriptorSources.isAmbiguous()) {
            log.warn("SQLite vector descriptor source unavailable or ambiguous; vector search remains disabled");
            return;
        }
        EmbeddingIndexDescriptor descriptor =
                descriptorSources.get().activeDescriptor().orElse(null);
        if (descriptor == null) {
            log.warn("SQLite active embedding descriptor unavailable; vector search remains disabled");
            return;
        }
        if (descriptor.vectorDimension() != configuredDimension) {
            log.warnf(
                    "SQLite vector dimension mismatch: descriptor=%d configured=%d; vector search disabled",
                    descriptor.vectorDimension(), configuredDimension);
            metadata.markUnavailable(VectorIndexNamespace.RAG_GLOBAL, descriptor);
            metadata.markUnavailable(VectorIndexNamespace.ARCHIVAL, descriptor);
            return;
        }
        verify(VectorIndexNamespace.RAG_GLOBAL, "fts.vec_global", descriptor);
        verify(VectorIndexNamespace.ARCHIVAL, "vec_archival", descriptor);
    }

    private void verify(VectorIndexNamespace namespace, String tableName, EmbeddingIndexDescriptor descriptor) {
        try {
            long count = jdbi.withHandle(handle -> handle.createQuery("SELECT count(*) FROM " + tableName)
                    .mapTo(Long.class)
                    .one());
            metadata.verifyOrInitialize(namespace, descriptor, count == 0);
        } catch (RuntimeException e) {
            metadata.markUnavailable(namespace, descriptor);
            log.warnf(
                    "SQLite vector namespace [%s] could not be inspected; vector search disabled: %s",
                    namespace.persistentId(), LogUtil.rootMessage(e));
        }
    }
}
