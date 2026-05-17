package com.arcclaw.persistence.sqlite;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.common.util.LogUtil;
import com.arcclaw.persistence.api.MemoryChunkDto;
import com.arcclaw.persistence.sqlite.entity.RagChunkEntity;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class GlobalKnowledgeBase {

    private static final Logger log = Logger.getLogger(GlobalKnowledgeBase.class);

    @Inject EntityManager em;
    @Inject EmbeddingModel embeddingModel;
    @Inject FtsConnectionInterceptor ftsInterceptor;

    @ConfigProperty(name = "arcclaw.rag.embed-dimensions", defaultValue = "768")
    int embedDimensions;

    @Transactional
    public String ingest(String text) {
        if (!ftsInterceptor.isVectorSearchEnabled()) {
            log.debugf("GlobalKnowledgeBase: vec0 not available, skipping ingest.");
            return null;
        }
        try {
            String chunkId = UUID.randomUUID().toString();
            Embedding embedding = embeddingModel.embed(text).content();
            float[] vector = embedding.vector();

            em.createNativeQuery(
                "INSERT OR REPLACE INTO fts.vec_global(chunk_id, embedding) VALUES (?1, ?2)")
                .setParameter(1, chunkId)
                .setParameter(2, floatArrayToJson(vector))
                .executeUpdate();

            // Persist raw text for hydration at search time.
            var ragChunk = new RagChunkEntity();
            ragChunk.chunkId = chunkId;
            ragChunk.text = text;
            ragChunk.source = "System"; // Example default source
            ragChunk.createdAt = Instant.now();
            em.persist(ragChunk);

            return chunkId;
        } catch (Exception e) {
            log.warnf("GlobalKnowledgeBase ingest failed: %s", LogUtil.rootMessage(e));
            return null;
        }
    }

    @Transactional
    public List<MemoryChunkDto> search(String query, int topN) {
        if (!ftsInterceptor.isVectorSearchEnabled()) {
            return List.of();
        }
        try {
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            float[] vector = queryEmbedding.vector();

            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                "SELECT chunk_id, vec_distance_cosine(embedding, ?1) AS distance " +
                "FROM fts.vec_global " +
                "ORDER BY distance ASC " +
                "LIMIT ?2")
                .setParameter(1, floatArrayToJson(vector))
                .setParameter(2, topN)
                .getResultList();

            List<MemoryChunkDto> results = new ArrayList<>();
            for (Object[] row : rows) {
                String chunkId = (String) row[0];
                double score = 1.0 - ((Number) row[1]).doubleValue(); // cosine distance → similarity
                RagChunkEntity chunkEntity = em.find(RagChunkEntity.class, chunkId);
                String text = chunkEntity != null ? chunkEntity.text : "";
                results.add(new MemoryChunkDto(chunkId, text, score));
            }
            return results;
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
}

