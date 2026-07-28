package com.amsha.persistence.sqlite;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.amsha.common.util.LogUtil;
import com.amsha.persistence.api.MemoryChunkDto;
import com.amsha.persistence.api.VectorIndexMetadataPort;
import com.amsha.persistence.api.VectorIndexNamespace;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "sqlite", enableIfMissing = true)
public class GlobalKnowledgeBase {

    private static final Logger log = Logger.getLogger(GlobalKnowledgeBase.class);

    @Inject
    Jdbi jdbi;

    @Inject
    EmbeddingModel embeddingModel;

    @Inject
    FtsConnectionInterceptor ftsInterceptor;

    @Inject
    VectorIndexMetadataPort vectorIndexMetadata;

    @ConfigProperty(name = "amsha.rag.embed-dimensions", defaultValue = "768")
    int embedDimensions;

    public String ingest(String text) {
        if (text == null || text.isBlank()) {
            log.debug("GlobalKnowledgeBase: skipping blank ingest text.");
            return null;
        }
        if (!isVectorEnabled()) {
            log.debugf("GlobalKnowledgeBase: compatible vector index unavailable, skipping ingest.");
            return null;
        }
        try {
            String chunkId = UUID.randomUUID().toString();
            Embedding embedding = embeddingModel.embed(text).content();
            float[] vector = embedding.vector();

            jdbi.useTransaction(handle -> {
                handle.createUpdate("INSERT OR REPLACE INTO fts.vec_global(chunk_id, embedding) VALUES (?, ?)")
                        .bind(0, chunkId)
                        .bind(1, floatArrayToJson(vector))
                        .execute();

                handle.createUpdate(
                                "INSERT OR REPLACE INTO rag_chunk(chunk_id, text, source, created_at) VALUES (?, ?, ?, ?)")
                        .bind(0, chunkId)
                        .bind(1, text)
                        .bind(2, "System")
                        .bind(3, Instant.now().toString())
                        .execute();
            });

            return chunkId;
        } catch (Exception e) {
            log.warnf("GlobalKnowledgeBase ingest failed: %s", LogUtil.rootMessage(e));
            return null;
        }
    }

    public List<MemoryChunkDto> search(String query, int topN) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if (!isVectorEnabled()) {
            return List.of();
        }
        try {
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            float[] vector = queryEmbedding.vector();

            return jdbi.withHandle(handle -> {
                List<Object[]> rows = handle.createQuery(
                                "SELECT chunk_id, vec_distance_cosine(embedding, ?1) AS distance "
                                        + "FROM fts.vec_global "
                                        + "ORDER BY distance ASC "
                                        + "LIMIT ?2")
                        .bind(0, floatArrayToJson(vector))
                        .bind(1, topN)
                        .map((rs, ctx) -> new Object[] {rs.getString("chunk_id"), rs.getDouble("distance")})
                        .list();

                List<MemoryChunkDto> results = new ArrayList<>();
                for (Object[] row : rows) {
                    String chunkId = (String) row[0];
                    double score = 1.0 - ((Number) row[1]).doubleValue();

                    String text = handle.createQuery("SELECT text FROM rag_chunk WHERE chunk_id = ?")
                            .bind(0, chunkId)
                            .mapTo(String.class)
                            .findOne()
                            .orElse("");

                    results.add(new MemoryChunkDto(chunkId, text, score));
                }
                return results;
            });
        } catch (Exception e) {
            log.warnf("GlobalKnowledgeBase search failed: %s", LogUtil.rootMessage(e));
            return List.of();
        }
    }

    static String floatArrayToJson(float[] embedding) {
        if (embedding == null || embedding.length == 0) return "[]";
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(Float.toString(embedding[i]));
        }
        sb.append(']');
        return sb.toString();
    }

    private boolean isVectorEnabled() {
        return ftsInterceptor.isVectorSearchEnabled()
                && vectorIndexMetadata.isVectorEnabled(VectorIndexNamespace.RAG_GLOBAL);
    }
}
