package com.arcclaw.rag;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.persistence.FtsConnectionInterceptor;
import com.arcclaw.core.state.CompactionSummaryEntity;
import com.arcclaw.core.state.CompactionSummaryRepository;
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
    @Inject CompactionSummaryRepository compactionRepo;

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
                .setParameter(2, Arrays.toString(vector))
                .executeUpdate();

            // Persist raw text for hydration at search time.
            // "rag:" prefix avoids PK collision with real session IDs.
            var ragChunk = new CompactionSummaryEntity();
            ragChunk.sessionId = "rag:" + chunkId;
            ragChunk.summary = text;
            ragChunk.createdAt = Instant.now();
            ragChunk.compactedAt = Instant.now();
            ragChunk.messagesCompacted = 0;
            em.persist(ragChunk);

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
                "SELECT chunk_id, vec_distance_cosine(embedding, ?1) AS distance " +
                "FROM fts.vec_global " +
                "ORDER BY distance ASC " +
                "LIMIT ?2")
                .setParameter(1, Arrays.toString(vector))
                .setParameter(2, topN)
                .getResultList();

            List<ScoredChunk> results = new ArrayList<>();
            for (Object[] row : rows) {
                String chunkId = (String) row[0];
                double score = 1.0 - ((Number) row[1]).doubleValue(); // cosine distance → similarity
                String text = compactionRepo.findLatestBySessionId("rag:" + chunkId)
                    .map(e -> e.summary)
                    .orElse("");
                results.add(new ScoredChunk(chunkId, text, score));
            }
            return results;
        } catch (Exception e) {
            log.warnf("GlobalKnowledgeBase search failed: %s", e.getMessage());
            return List.of();
        }
    }
}
