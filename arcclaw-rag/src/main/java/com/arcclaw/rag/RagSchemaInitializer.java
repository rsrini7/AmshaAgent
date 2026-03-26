package com.arcclaw.rag;

import java.sql.Connection;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.annotation.Priority;
import jakarta.interceptor.Interceptor;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.persistence.FtsConnectionInterceptor;
import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class RagSchemaInitializer {

    private static final Logger log = Logger.getLogger(RagSchemaInitializer.class);

    @Inject
    EntityManager em;

    @Inject
    AgroalDataSource dataSource;

    @Inject
    FtsConnectionInterceptor ftsInterceptor;

    @ConfigProperty(name = "arcclaw.rag.embed-dimensions", defaultValue = "768")
    int embedDimensions;

    @Priority(Interceptor.Priority.APPLICATION + 100)
    void onStart(@Observes @Priority(Interceptor.Priority.APPLICATION + 100) StartupEvent ev) {
        // Acquire a connection to trigger FtsConnectionInterceptor.onConnectionAcquire()
        // which calls enable_load_extension + load_extension(vec-extension-path).
        // Without this, isVectorSearchEnabled() is always false at startup because
        // no pooled connection has been acquired yet.
        try (Connection _ = dataSource.getConnection()) {
            log.debugf("RagSchemaInitializer: connection acquired to trigger vec0 load.");
        } catch (Exception e) {
            log.warnf("RagSchemaInitializer: connection warm-up failed: %s", e.getMessage());
            return;
        }

        if (!ftsInterceptor.isVectorSearchEnabled()) {
            log.warn("RagSchemaInitializer: vec0 not loaded — skipping vec_global DDL. " +
                "Ensure the native binary exists at arcclaw.rag.vec-extension-path " +
                "(default: ./lib/vec0). Download vec0.so/.dll/.dylib from: " +
                "https://github.com/asg017/sqlite-vec/releases/tag/v0.1.6");
            return;
        }

        boolean vecLoaded = ftsInterceptor.isVectorSearchEnabled();
        log.infof("RAG STATUS vec0=%s | FTS5=enabled | dims=%d | path=%s",
            vecLoaded ? "✓ LOADED" : "✗ degraded (FTS5-only)",
            embedDimensions,
            vecLoaded ? "active" : "check ARCCLAW_SQLITE_VEC_PATH");

        if (!vecLoaded) return;

        createVecGlobal();
    }

    @Transactional
    void createVecGlobal() {
        try {
            em.createNativeQuery(
                "CREATE VIRTUAL TABLE IF NOT EXISTS fts.vec_global " +
                "USING vec0(" +
                "    chunk_id TEXT PRIMARY KEY," +
                "    embedding float[" + embedDimensions + "]" +
                ")"
            ).executeUpdate();
            log.infof("RagSchemaInitializer: fts.vec_global ensured (dims=%d).", embedDimensions);
        } catch (Exception e) {
            log.warnf("RagSchemaInitializer: Could not create vec_global: %s", e.getMessage());
        }
    }
}
