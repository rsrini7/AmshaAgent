package com.arcclaw.persistence.postgres;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.persistence.api.BackendCapabilities;
import com.arcclaw.persistence.api.CompactionStorePort;
import com.arcclaw.persistence.api.CompactionSummaryDto;
import com.arcclaw.persistence.api.IngestResult;
import com.arcclaw.persistence.api.MemoryChunkDto;
import com.arcclaw.persistence.api.MemoryEntryDto;
import com.arcclaw.persistence.api.MemoryRetrievalPort;
import com.arcclaw.persistence.postgres.jdbi.RagChunkJdbiDao;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "postgres")
public class PostgresMemoryRetrievalAdapter implements MemoryRetrievalPort {

    private static final Logger log = Logger.getLogger(PostgresMemoryRetrievalAdapter.class);
    private static final double RRF_K = 60.0;

    private final Jdbi jdbi;
    private final CompactionStorePort compactionRepo;
    private final EmbeddingModel embeddingModel;
    private final int maxSourceChars;

    @Inject
    public PostgresMemoryRetrievalAdapter(
            Jdbi jdbi,
            CompactionStorePort compactionRepo,
            EmbeddingModel embeddingModel,
            @ConfigProperty(name = "arcclaw.rag.max-source-chars", defaultValue = "400") int maxSourceChars) {
        this.jdbi = jdbi;
        this.compactionRepo = compactionRepo;
        this.embeddingModel = embeddingModel;
        this.maxSourceChars = maxSourceChars;
    }

    protected PostgresMemoryRetrievalAdapter() {
        this(null, null, null, 400);
    }

    @Override
    public List<MemoryChunkDto> search(String query, int topK, String userId) {
        if (query == null || query.isBlank()) return List.of();

        List<CompactionSummaryDto> ftsEntities = compactionRepo.search(userId, query, topK * 2);
        List<MemoryChunkDto> ftsResults = new ArrayList<>();
        for (int i = 0; i < ftsEntities.size(); i++) {
            var e = ftsEntities.get(i);
            ftsResults.add(new MemoryChunkDto(e.sessionId(), e.summary(), 1.0 / (i + 1)));
        }

        List<MemoryChunkDto> ragResults = jdbi.withExtension(RagChunkJdbiDao.class, dao -> dao.search(query, topK * 2));
        for (int i = 0; i < ragResults.size(); i++) {
            var r = ragResults.get(i);
            ftsResults.add(new MemoryChunkDto(r.chunkId(), r.text(), 1.0 / (i + 1)));
        }

        List<MemoryChunkDto> vectorResults = searchVector(query, topK * 2);

        List<MemoryChunkDto> merged = reciprocalRankFusion(topK, ftsResults, vectorResults);

        return merged.stream()
            .map(chunk -> {
                String text = chunk.text();
                if (text != null && text.length() > maxSourceChars) {
                    text = text.substring(0, maxSourceChars) + "\u2026";
                }
                return new MemoryChunkDto(chunk.chunkId(), text, chunk.score());
            })
            .toList();
    }

    private List<MemoryChunkDto> searchVector(String query, int topN) {
        try {
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            String json = floatArrayToJson(queryEmbedding.vector());
            return jdbi.withExtension(RagChunkJdbiDao.class, dao -> dao.searchVector(json, topN));
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public IngestResult index(MemoryEntryDto entry) {
        String chunkId = entry.id() != null ? entry.id() : UUID.randomUUID().toString();
        jdbi.useTransaction(handle -> {
            var dao = handle.attach(RagChunkJdbiDao.class);
            dao.insert(chunkId, entry.text(), "System", Instant.now());
        });
        // Embedding runs outside the transaction — embed calls are slow I/O
        // that corrupt Agroal connection state if they fail inside a tx
        try {
            Embedding embedding = embeddingModel.embed(entry.text()).content();
            jdbi.useExtension(RagChunkJdbiDao.class, dao ->
                dao.upsertEmbedding(chunkId, floatArrayToJson(embedding.vector())));
        } catch (Exception e) {
            log.warnf("Embedding unavailable for chunk %s: %s", chunkId, e.getMessage());
        }
        return new IngestResult(chunkId, false);
    }

    @Override
    public BackendCapabilities capabilities() {
        return new BackendCapabilities(
            true,  // supportsFullTextSearch
            true,  // supportsVectorSearch
            true,  // supportsAdvisoryLocks
            false  // supportsNestedTransactions
        );
    }

    @SafeVarargs
    final List<MemoryChunkDto> reciprocalRankFusion(int topN, List<MemoryChunkDto>... rankedLists) {
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, MemoryChunkDto> chunkById = new LinkedHashMap<>();

        for (List<MemoryChunkDto> list : rankedLists) {
            for (int i = 0; i < list.size(); i++) {
                MemoryChunkDto chunk = list.get(i);
                int rank = i + 1;
                double contribution = 1.0 / (RRF_K + rank);
                rrfScores.merge(chunk.chunkId(), contribution, Double::sum);
                chunkById.merge(chunk.chunkId(), chunk, (existing, incoming) ->
                    (existing.text() == null || existing.text().isBlank()) ? incoming : existing);
            }
        }

        return rrfScores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(topN)
            .map(e -> {
                MemoryChunkDto orig = chunkById.get(e.getKey());
                return new MemoryChunkDto(orig.chunkId(), orig.text(), e.getValue());
            })
            .toList();
    }

    static String floatArrayToJson(float[] arr) {
        if (arr == null || arr.length == 0) return "[]";
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(Float.toString(arr[i]));
        }
        sb.append(']');
        return sb.toString();
    }
}