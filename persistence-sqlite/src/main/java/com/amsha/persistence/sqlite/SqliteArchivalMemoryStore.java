package com.amsha.persistence.sqlite;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.amsha.core.memory.tiered.TieredBackend;
import com.amsha.persistence.api.ArchivalChunkDto;
import com.amsha.persistence.api.ArchivalMemoryStorePort;
import com.amsha.persistence.api.ArchivalSearchQuery;
import com.amsha.persistence.sqlite.jdbi.ArchivalMemoryJdbiDao;

import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@TieredBackend("sqlite")
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "sqlite", enableIfMissing = true)
public class SqliteArchivalMemoryStore implements ArchivalMemoryStorePort {

    private static final Logger log = Logger.getLogger(SqliteArchivalMemoryStore.class);

    /** RRF constant — same as FsMemoryRetrievalAdapter. */
    private static final int RRF_K = 60;

    private final Jdbi jdbi;

    @Inject
    VectorIngestionService vectorIngestion;

    @Inject
    public SqliteArchivalMemoryStore(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected SqliteArchivalMemoryStore() {
        this(null);
    }

    @Override
    public void upsert(ArchivalChunkDto chunk) {
        jdbi.useExtension(ArchivalMemoryJdbiDao.class, dao -> dao.upsert(chunk));
    }

    @Override
    public List<ArchivalChunkDto> search(ArchivalSearchQuery query) {
        boolean chunkTypesEmpty =
                query.chunkTypes() == null || query.chunkTypes().isEmpty();
        List<String> chunkTypes = chunkTypesEmpty ? List.of("") : List.copyOf(query.chunkTypes());

        // BM25-only path: useVector=false or vector unavailable
        if (!query.useVector() || !vectorIngestion.isVectorAvailable()) {
            return bm25Search(query.userId(), query.query(), query.limit(), chunkTypesEmpty, chunkTypes);
        }

        // Hybrid path: BM25 + vector, merged via RRF
        return hybridSearch(query, chunkTypesEmpty, chunkTypes);
    }

    @Override
    public Optional<ArchivalChunkDto> getById(String chunkId) {
        return jdbi.withExtension(ArchivalMemoryJdbiDao.class, dao -> dao.getById(chunkId));
    }

    @Override
    public void incrementAccessCount(String chunkId) {
        jdbi.useExtension(ArchivalMemoryJdbiDao.class, dao -> dao.incrementAccessCount(chunkId));
    }

    @Override
    public void incrementAccessCounts(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return;
        jdbi.useExtension(ArchivalMemoryJdbiDao.class, dao -> dao.incrementAccessCounts(chunkIds));
    }

    @Override
    public void delete(String chunkId) {
        jdbi.useExtension(ArchivalMemoryJdbiDao.class, dao -> dao.delete(chunkId));
    }

    @Override
    public void evictLowConfidence(double threshold, int minAgeDays) {
        jdbi.useExtension(ArchivalMemoryJdbiDao.class, dao -> dao.evictLowConfidence(threshold, minAgeDays));
    }

    /**
     * Returns the total number of archival chunks for a given user.
     */
    public long countByUserId(String userId) {
        return jdbi.withExtension(ArchivalMemoryJdbiDao.class, dao -> dao.countByUserId(userId));
    }

    /**
     * Lists archival chunks for a given user with pagination.
     */
    public List<ArchivalChunkDto> listByUserId(String userId, int limit, int offset) {
        return jdbi.withExtension(ArchivalMemoryJdbiDao.class, dao -> dao.listByUserId(userId, limit, offset));
    }

    private List<ArchivalChunkDto> bm25Search(
            String userId, String query, int limit, boolean chunkTypesEmpty, List<String> chunkTypes) {
        return jdbi.withExtension(
                ArchivalMemoryJdbiDao.class, dao -> dao.search(userId, query, limit, chunkTypesEmpty, chunkTypes));
    }

    /**
     * Hybrid BM25 + vector search with Reciprocal Rank Fusion (RRF, k=60).
     * <p>
     * Runs both searches independently, then merges results by chunk_id.
     * RRF score = sum(1 / (k + rank)) for each result list where the chunk appears.
     * <p>
     * When {@code query.queryEmbedding()} is non-null, vector search is performed
     * via {@link VectorIngestionService#searchVector} and results are merged with BM25.
     * When null, falls back to BM25-only.
     */
    private List<ArchivalChunkDto> hybridSearch(
            ArchivalSearchQuery query, boolean chunkTypesEmpty, List<String> chunkTypes) {
        int searchLimit = Math.max(query.limit() * 2, 20); // oversample for merging

        // BM25 results
        List<ArchivalChunkDto> bm25Results =
                bm25Search(query.userId(), query.query(), searchLimit, chunkTypesEmpty, chunkTypes);

        // Vector results (requires embedding from tool path)
        float[] embedding = query.queryEmbedding();
        if (embedding == null || !vectorIngestion.isVectorAvailable()) {
            log.debugf("Hybrid search: no embedding or vec0 unavailable, BM25-only (%d results)", bm25Results.size());
            return bm25Results.stream().limit(query.limit()).toList();
        }

        try {
            List<Object[]> vectorRows = vectorIngestion.searchVector(embedding, searchLimit);
            List<String> vectorIds = vectorRows.stream().map(r -> (String) r[0]).toList();

            if (vectorIds.isEmpty()) {
                return bm25Results.stream().limit(query.limit()).toList();
            }

            // Batch fetch in single query (avoids N+1)
            Map<String, ArchivalChunkDto> chunkMap =
                    jdbi.withExtension(ArchivalMemoryJdbiDao.class, dao -> dao.getByIds(vectorIds)).stream()
                            .collect(Collectors.toMap(ArchivalChunkDto::chunkId, c -> c));

            // Preserve vector rank order for correct RRF scoring
            List<ArchivalChunkDto> vectorChunks = new ArrayList<>();
            for (String id : vectorIds) {
                ArchivalChunkDto chunk = chunkMap.get(id);
                if (chunk != null) vectorChunks.add(chunk);
            }

            log.debugf("Hybrid search: BM25=%d, vector=%d → RRF merge", bm25Results.size(), vectorChunks.size());
            return mergeRrf(bm25Results, vectorChunks, query.limit());
        } catch (Exception e) {
            log.warnf("Hybrid search: vector path failed (%s), falling back to BM25-only", e.getMessage());
            return bm25Results.stream().limit(query.limit()).toList();
        }
    }

    /**
     * Merge BM25 and vector results using Reciprocal Rank Fusion.
     * <p>
     * RRF score = sum(1 / (k + rank_i)) for each list where the chunk appears.
     * Returns results sorted by RRF score descending, limited to {@code limit}.
     */
    static List<ArchivalChunkDto> mergeRrf(
            List<ArchivalChunkDto> bm25, List<ArchivalChunkDto> vectorChunks, int limit) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, ArchivalChunkDto> chunkMap = new LinkedHashMap<>();

        // BM25 ranks (1-based)
        for (int i = 0; i < bm25.size(); i++) {
            String id = bm25.get(i).chunkId();
            scores.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
            chunkMap.putIfAbsent(id, bm25.get(i));
        }

        // Vector ranks (1-based)
        for (int i = 0; i < vectorChunks.size(); i++) {
            String id = vectorChunks.get(i).chunkId();
            scores.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
            chunkMap.putIfAbsent(id, vectorChunks.get(i));
        }

        // Sort by RRF score descending
        List<String> sortedIds = scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();

        List<ArchivalChunkDto> result = new ArrayList<>();
        for (String id : sortedIds) {
            ArchivalChunkDto chunk = chunkMap.get(id);
            if (chunk != null) {
                result.add(chunk);
            }
        }
        return result;
    }
}
