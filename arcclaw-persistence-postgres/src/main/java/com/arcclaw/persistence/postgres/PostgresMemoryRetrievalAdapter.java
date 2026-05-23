package com.arcclaw.persistence.postgres;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jdbi.v3.core.Jdbi;

/**
 * PostgreSQL implementation of MemoryRetrievalPort using JDBI.
 * Performs keyword search over compaction summaries and raw text chunks using Postgres native FTS.
 */
@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "postgres")
public class PostgresMemoryRetrievalAdapter implements MemoryRetrievalPort {

    private final Jdbi jdbi;
    private final CompactionStorePort compactionRepo;
    private final int maxSourceChars;

    @Inject
    public PostgresMemoryRetrievalAdapter(
            Jdbi jdbi,
            CompactionStorePort compactionRepo,
            @ConfigProperty(name = "arcclaw.rag.max-source-chars", defaultValue = "400") int maxSourceChars) {
        this.jdbi = jdbi;
        this.compactionRepo = compactionRepo;
        this.maxSourceChars = maxSourceChars;
    }

    protected PostgresMemoryRetrievalAdapter() {
        this(null, null, 400);
    }

    @Override
    public List<MemoryChunkDto> search(String query, int topK, String userId) {
        if (query == null || query.isBlank()) return List.of();
        
        List<MemoryChunkDto> results = new ArrayList<>();

        // 1. Search Compaction Summaries
        List<CompactionSummaryDto> ftsEntities = compactionRepo.search(userId, query, topK);
        for (int i = 0; i < ftsEntities.size(); i++) {
            var e = ftsEntities.get(i);
            results.add(new MemoryChunkDto(e.sessionId(), e.summary(), 1.0 / (i + 1)));
        }

        // 2. Search Rag Chunks
        List<MemoryChunkDto> ragChunks = jdbi.withExtension(RagChunkJdbiDao.class, dao -> dao.search(query, topK));
        for (int i = 0; i < ragChunks.size(); i++) {
            var rc = ragChunks.get(i);
            results.add(new MemoryChunkDto(rc.chunkId(), rc.text(), 1.0 / (i + 1)));
        }

        // 3. Limit and Truncate
        return results.stream()
            .limit(topK)
            .map(chunk -> {
                String text = chunk.text();
                if (text != null && text.length() > maxSourceChars) {
                    text = text.substring(0, maxSourceChars) + "…";
                }
                return new MemoryChunkDto(chunk.chunkId(), text, chunk.score());
            })
            .toList();
    }

    @Override
    public IngestResult index(MemoryEntryDto entry) {
        String chunkId = entry.id() != null ? entry.id() : UUID.randomUUID().toString();
        jdbi.useExtension(RagChunkJdbiDao.class, dao -> dao.insert(chunkId, entry.text(), "System", Instant.now()));
        return new IngestResult(chunkId, false);
    }

    @Override
    public BackendCapabilities capabilities() {
        return new BackendCapabilities(
            true,  // supportsFullTextSearch
            false, // supportsVectorSearch
            true,  // supportsAdvisoryLocks
            false  // supportsNestedTransactions
        );
    }
}
