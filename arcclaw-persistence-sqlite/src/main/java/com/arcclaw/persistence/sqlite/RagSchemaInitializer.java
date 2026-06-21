package com.arcclaw.persistence.sqlite;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;

import java.sql.Connection;

import com.arcclaw.common.util.LogUtil;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;

import io.agroal.api.AgroalDataSource;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "sqlite", enableIfMissing = true)
public class RagSchemaInitializer {

    private static final Logger log = Logger.getLogger(RagSchemaInitializer.class);

    @Inject
    Jdbi jdbi;

    @Inject
    AgroalDataSource dataSource;

    @Inject
    FtsConnectionInterceptor ftsInterceptor;

    @ConfigProperty(name = "arcclaw.rag.embed-dimensions", defaultValue = "768")
    int embedDimensions;

    @Priority(Interceptor.Priority.APPLICATION + 100)
    void onStart(@Observes @Priority(Interceptor.Priority.APPLICATION + 100) StartupEvent ev) {
        try (Connection _ = dataSource.getConnection()) {
            log.debugf("RagSchemaInitializer: connection acquired to trigger vec0 load.");
        } catch (Exception e) {
            log.warnf("RagSchemaInitializer: connection warm-up failed: %s", LogUtil.rootMessage(e));
            return;
        }

        if (!ftsInterceptor.isVectorSearchEnabled()) {
            log.warn("RagSchemaInitializer: vec0 not loaded - skipping vec_global DDL. "
                    + "Ensure the native binary exists at arcclaw.rag.vec-extension-path "
                    + "(default: ./lib/vec0). Download vec0.so/.dll/.dylib from: "
                    + "https://github.com/asg017/sqlite-vec/releases/tag/v0.1.6");
            return;
        }

        boolean vecLoaded = ftsInterceptor.isVectorSearchEnabled();
        log.infof(
                "RAG STATUS vec0=%s | FTS5=enabled | dims=%d | path=%s",
                vecLoaded ? "\u2713 LOADED" : "\u2717 degraded (FTS5-only)",
                embedDimensions,
                vecLoaded ? "active" : "check ARCCLAW_RAG_VEC_PATH");

        if (!vecLoaded) return;

        createVecGlobal();
    }

    void createVecGlobal() {
        try {
            jdbi.withHandle(handle -> {
                handle.createUpdate("CREATE VIRTUAL TABLE IF NOT EXISTS fts.vec_global " + "USING vec0("
                                + "    chunk_id TEXT PRIMARY KEY,"
                                + "    embedding float["
                                + embedDimensions + "]" + ")")
                        .execute();
                return null;
            });
            log.infof("RagSchemaInitializer: fts.vec_global ensured (dims=%d).", embedDimensions);
        } catch (Exception e) {
            log.warnf("RagSchemaInitializer: Could not create vec_global: %s", LogUtil.rootMessage(e));
        }
    }
}
