package com.amsha.persistence.sqlite;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;

import io.agroal.api.AgroalDataSource;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.runtime.StartupEvent;

/**
 * Creates the vec_archival virtual table for vector embeddings on archival chunks.
 * <p>
 * Runs at priority 200 — after {@link Vec0NativeLibraryInstaller} (priority 10)
 * and {@link RagSchemaInitializer} (priority 100). Guards on vec0 availability:
 * if the native extension failed to load, this initializer logs a warning and
 * skips DDL, allowing the app to degrade to BM25-only search.
 * <p>
 * vec_archival lives in the main database (not the fts schema) because
 * archival_chunks is in the main DB.
 */
@ApplicationScoped
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "sqlite", enableIfMissing = true)
public class ArchivalVecSchemaInitializer {

    private static final Logger log = Logger.getLogger(ArchivalVecSchemaInitializer.class);

    @Inject
    Jdbi jdbi;

    @Inject
    AgroalDataSource dataSource;

    @Inject
    FtsConnectionInterceptor ftsInterceptor;

    @ConfigProperty(name = "amsha.rag.embed-dimensions", defaultValue = "768")
    int embedDimensions;

    void onStart(@Observes @Priority(200) StartupEvent ev) {
        if (!ftsInterceptor.isVectorSearchEnabled()) {
            log.warn("ArchivalVecSchemaInitializer: vec0 not available — vec_archival skipped, "
                    + "archival hybrid search degraded to BM25-only.");
            return;
        }

        try {
            jdbi.withHandle(handle -> {
                handle.createUpdate("CREATE VIRTUAL TABLE IF NOT EXISTS vec_archival " + "USING vec0("
                                + "    chunk_id TEXT PRIMARY KEY,"
                                + "    embedding float["
                                + embedDimensions + "]" + ")")
                        .execute();
                return null;
            });
            log.infof("ArchivalVecSchemaInitializer: vec_archival ensured (dims=%d).", embedDimensions);
        } catch (Exception e) {
            log.warnf(
                    "ArchivalVecSchemaInitializer: Could not create vec_archival: %s. "
                            + "Archival hybrid search degraded to BM25-only.",
                    e.getMessage());
        }
    }
}
