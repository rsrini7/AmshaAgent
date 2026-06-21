package com.arcclaw.persistence.sqlite;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.arcclaw.common.util.LogUtil;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.properties.IfBuildProperty;

/**
 * Serializes sqlite-vec vector writes onto a single platform thread.
 * <p>
 * sqlite-vec 0.1.x is not thread-safe for concurrent writes — native C state
 * can corrupt if two virtual threads race on the same vec0 virtual table.
 * This service guarantees all vector writes execute on one specific OS thread
 * via {@link Executors#newSingleThreadExecutor()} with a platform thread factory.
 * <p>
 * Reads (vector searches) remain fully concurrent — no serialization needed.
 */
@ApplicationScoped
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "sqlite", enableIfMissing = true)
public class VectorIngestionService {

    private static final Logger log = Logger.getLogger(VectorIngestionService.class);

    @Inject
    Jdbi jdbi;

    @Inject
    FtsConnectionInterceptor ftsInterceptor;

    @ConfigProperty(name = "arcclaw.rag.embed-dimensions", defaultValue = "768")
    int embedDimensions;

    private final ExecutorService writerExecutor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("archival-vec-writer").daemon(true).factory());

    /**
     * Ingest a chunk's embedding into vec_archival. Runs on a single platform
     * thread to guarantee native sqlite-vec write safety.
     *
     * @param chunkId    the chunk ID (must already exist in archival_chunks)
     * @param embedding  the embedding vector as float[]
     * @return a future that completes when the write is done
     */
    public CompletableFuture<Void> ingestVector(String chunkId, float[] embedding) {
        return CompletableFuture.runAsync(
                        () -> {
                            jdbi.useHandle(handle -> {
                                handle.createUpdate(
                                                "INSERT OR REPLACE INTO vec_archival(chunk_id, embedding) VALUES (?, ?)")
                                        .bind(0, chunkId)
                                        .bind(1, embedding)
                                        .execute();
                            });
                        },
                        writerExecutor)
                .exceptionally(ex -> {
                    log.errorf(
                            "VectorIngestionService: vector write failed for chunk %s: %s",
                            chunkId, LogUtil.rootMessage(ex));
                    return null;
                });
    }

    /**
     * Delete a chunk's embedding from vec_archival.
     */
    public CompletableFuture<Void> deleteVector(String chunkId) {
        return CompletableFuture.runAsync(
                        () -> {
                            jdbi.useHandle(handle -> {
                                handle.createUpdate("DELETE FROM vec_archival WHERE chunk_id = ?")
                                        .bind(0, chunkId)
                                        .execute();
                            });
                        },
                        writerExecutor)
                .exceptionally(ex -> {
                    log.errorf(
                            "VectorIngestionService: vector delete failed for chunk %s: %s",
                            chunkId, LogUtil.rootMessage(ex));
                    return null;
                });
    }

    /**
     * Query vec_archival for nearest neighbors by cosine distance.
     * This is a read operation — no write serialization needed.
     *
     * @param queryEmbedding the query embedding vector
     * @param limit          max results
     * @return array of [chunkId, distance] pairs, ordered by distance ASC
     */
    public List<Object[]> searchVector(float[] queryEmbedding, int limit) {
        return jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT chunk_id, vec_distance_cosine(embedding, :embedding) AS distance "
                                + "FROM vec_archival "
                                + "ORDER BY distance ASC "
                                + "LIMIT :limit")
                .bind("embedding", queryEmbedding)
                .bind("limit", limit)
                .map((rs, ctx) -> new Object[] {rs.getString("chunk_id"), rs.getDouble("distance")})
                .list());
    }

    /**
     * Check if vec0 loaded and vec_archival table is available.
     */
    public boolean isVectorAvailable() {
        return ftsInterceptor.isVectorSearchEnabled();
    }

    @PreDestroy
    void shutdown() {
        writerExecutor.shutdown();
        try {
            if (!writerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("VectorIngestionService: writer did not drain in 5s, forcing shutdown");
                writerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writerExecutor.shutdownNow();
        }
    }
}
