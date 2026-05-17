package com.arcclaw.persistence.sqlite;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;

import com.arcclaw.common.util.LogUtil;
import com.arcclaw.core.scope.WorkspaceScoped;
import com.arcclaw.persistence.api.MemoryChunkDto;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.jboss.logging.Logger;

@WorkspaceScoped
public class SessionKnowledgeBase {

    private static final Logger log = Logger.getLogger(SessionKnowledgeBase.class);

    @Inject EmbeddingModel embeddingModel;

    private InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();

    public void ingest(String chunkId, String text) {
        try {
            Embedding embedding = embeddingModel.embed(text).content();
            Metadata metadata = Metadata.from("id", chunkId);
            store.add(chunkId, embedding, TextSegment.from(text, metadata));
        } catch (Exception e) {
            log.warnf("SessionKnowledgeBase ingest failed: %s", LogUtil.rootMessage(e));
        }
    }

    // Original - unchanged, kept for callers that don't need score filtering.
    public List<MemoryChunkDto> search(String query, int topN) {
        return search(query, topN, 0.0);
    }

    // ── New overload: minScore filters at the store level, avoiding the cost of
    // returning and then discarding low-relevance matches at the enricher layer.
    public List<MemoryChunkDto> search(String query, int topN, double minScore) {
        try {
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(topN)
                    .minScore(minScore)
                    .build();

            EmbeddingSearchResult<TextSegment> result = store.search(request);

            return result.matches().stream()
                    .map(match -> new MemoryChunkDto(
                            match.embedded().metadata().getString("id"),
                            match.embedded().text(),
                            match.score()))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warnf("SessionKnowledgeBase search failed: %s", LogUtil.rootMessage(e));
            return List.of();
        }
    }

    @PreDestroy
    void destroy() {
        try {
            store = new InMemoryEmbeddingStore<>();
            log.debugf("SessionKnowledgeBase wiped on workspace teardown.");
        } catch (Exception e) {
            log.warnf("SessionKnowledgeBase destroy failed: %s", LogUtil.rootMessage(e));
        }
    }
}
