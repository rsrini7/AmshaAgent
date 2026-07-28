package com.amsha.persistence.fs;

import dev.langchain4j.model.embedding.EmbeddingModel;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.amsha.persistence.api.BackendCapabilities;
import com.amsha.persistence.api.IngestResult;
import com.amsha.persistence.api.MemoryChunkDto;
import com.amsha.persistence.api.MemoryEntryDto;
import com.amsha.persistence.api.MemoryRetrievalPort;
import com.amsha.persistence.fs.lucene.LuceneIndexManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "fs")
public class FsMemoryRetrievalAdapter implements MemoryRetrievalPort {

    private static final Logger LOG = Logger.getLogger(FsMemoryRetrievalAdapter.class);

    private static final String DOMAIN = "memory-index";

    @Inject
    FilesystemFileStore store;

    @Inject
    FsStoreProperties properties;

    @Inject
    Instance<EmbeddingModel> embeddingModel;

    @Inject
    ObjectMapper objectMapper;

    private LuceneIndexManager lucene;
    private boolean vectorSearchEnabled;
    private int vectorDimension;
    private volatile boolean dimensionValidated;

    @PostConstruct
    void init() {
        vectorDimension = properties.vectorDimension();
        Path luceneIndexDir = Path.of(properties.luceneIndexDir());
        Path luceneDir = luceneIndexDir.isAbsolute()
                ? luceneIndexDir.normalize()
                : Path.of(properties.dataDir(), properties.luceneIndexDir()).normalize();
        vectorSearchEnabled = embeddingModel != null && !embeddingModel.isUnsatisfied() && vectorDimension > 0;
        lucene = new LuceneIndexManager(luceneDir, vectorDimension);
        rebuildFromJson();
    }

    @PreDestroy
    void destroy() {
        if (lucene != null) {
            try {
                lucene.close();
            } catch (Exception e) {
                LOG.warnf("Failed to close Lucene index: %s", e.getMessage());
            }
        }
    }

    void rebuildFromJson() {
        List<String> files = store.list(List.of(DOMAIN), ".json");
        for (String f : files) {
            String id = f.replace(".json", "");
            store.readOptional(List.of(DOMAIN, f), IndexEntry.class).ifPresent(entry -> {
                String mdJson = serializeMetadata(entry.metadata);
                lucene.index(id, entry.text, mdJson, embeddingFor(entry.text, id));
            });
        }
        if (!files.isEmpty()) {
            LOG.infof("Rebuilt Lucene index from %d JSON entries", files.size());
        }
    }

    @Override
    public List<MemoryChunkDto> search(String query, int topK, String userId) {
        // userId is ignored — FS backend is global (no user isolation yet)
        if (vectorSearchEnabled) {
            try {
                var embeddingResult = embeddingModel.get().embed(query);
                validateDimension(embeddingResult.content().vector());
                if (!vectorSearchEnabled) {
                    return bm25Search(query, topK);
                }
                return lucene.searchHybrid(query, embeddingResult.content().vector(), topK).stream()
                        .map(r -> new MemoryChunkDto(r.id(), r.content(), r.score()))
                        .toList();
            } catch (Exception e) {
                LOG.warnf("Vector search failed for query (falling back to BM25): %s", e.getMessage());
                return bm25Search(query, topK);
            }
        }
        return bm25Search(query, topK);
    }

    @Override
    public IngestResult index(MemoryEntryDto entry) {
        String id = entry.id() != null ? entry.id() : UUID.randomUUID().toString();
        String text = entry.text();
        String metadataJson = serializeMetadata(entry.metadata());
        lucene.index(id, text, metadataJson, embeddingFor(text, id));
        store.write(List.of(DOMAIN, id + ".json"), new IndexEntry(id, text, entry.metadata()));
        return new IngestResult(id, true);
    }

    @Override
    public BackendCapabilities capabilities() {
        return new BackendCapabilities(true, vectorSearchEnabled, false, false);
    }

    private List<MemoryChunkDto> bm25Search(String query, int topK) {
        return lucene.searchBM25(query, topK).stream()
                .map(r -> new MemoryChunkDto(r.id(), r.content(), r.score()))
                .toList();
    }

    private float[] embeddingFor(String text, String id) {
        if (!vectorSearchEnabled || text == null || text.isBlank()) return null;
        try {
            float[] embedding = embeddingModel.get().embed(text).content().vector();
            validateDimension(embedding);
            return vectorSearchEnabled ? embedding : null;
        } catch (Exception e) {
            LOG.warnf("Embedding failed for document %s (indexing without vector): %s", id, e.getMessage());
            return null;
        }
    }

    private void validateDimension(float[] vector) {
        if (vector == null || vector.length == 0) {
            LOG.warn("Vector search disabled: embedding returned null/empty vector");
            vectorSearchEnabled = false;
            return;
        }
        if (dimensionValidated) return;
        dimensionValidated = true;
        if (vector.length != vectorDimension) {
            LOG.warnf(
                    "Vector dimension mismatch: configured=%d, model=%d — disabling vector search",
                    vectorDimension, vector.length);
            vectorSearchEnabled = false;
        }
    }

    private String serializeMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            LOG.warnf("Failed to serialize metadata: %s", e.getMessage());
            return null;
        }
    }

    private record IndexEntry(String id, String text, Map<String, String> metadata) {}
}
