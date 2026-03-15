package com.arcclaw.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class GlobalKnowledgeBase {

    private static final Logger log = Logger.getLogger(GlobalKnowledgeBase.class);

    @Inject EntityManager em;
    @Inject EmbeddingModel embeddingModel;
    @Inject com.arcclaw.core.persistence.FtsConnectionInterceptor ftsInterceptor;

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
                "INSERT OR REPLACE INTO fts.vec_global(chunk_id, embedding) VALUES (?, ?)")
                .setParameter(1, chunkId)
                .setParameter(2, vector)
                .executeUpdate();

            return chunkId;
        } catch (Exception e) {
            log.warnf("GlobalKnowledgeBase ingest failed: %s", e.getMessage());
            return null;
        }
    }

    public List<ScoredChunk> search(String query, int topN) {
        if (!ftsInterceptor.isVectorSearchEnabled()) {
            return List.of();
        }
        try {
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            float[] vector = queryEmbedding.vector();

            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                "SELECT chunk_id, vec_distance_cosine(embedding, ?) AS distance " +
                "FROM fts.vec_global " +
                "ORDER BY distance ASC " +
                "LIMIT ?")
                .setParameter(1, vector)
                .setParameter(2, topN)
                .getResultList();

            List<ScoredChunk> results = new ArrayList<>();
            for (Object[] row : rows) {
                String chunkId = (String) row[0];
                double score = 1.0 - ((Number) row[1]).doubleValue(); // cosine distance → similarity
                results.add(new ScoredChunk(chunkId, "", score));
            }
            return results;
        } catch (Exception e) {
            log.warnf("GlobalKnowledgeBase search failed: %s", e.getMessage());
            return List.of();
        }
    }
}
