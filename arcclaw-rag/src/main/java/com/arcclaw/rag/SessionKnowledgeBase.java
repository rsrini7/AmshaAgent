package com.arcclaw.rag;

import com.arcclaw.core.scope.WorkspaceScoped;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.stream.Collectors;

@WorkspaceScoped
public class SessionKnowledgeBase {

    private static final Logger log = Logger.getLogger(SessionKnowledgeBase.class);

    @Inject EmbeddingModel embeddingModel;

    private InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();

    public void ingest(String chunkId, String text) {
        try {
            Embedding embedding = embeddingModel.embed(text).content();
            // Store chunkId in metadata so search() can retrieve it
            Metadata metadata = Metadata.from("id", chunkId);
            store.add(chunkId, embedding, TextSegment.from(text, metadata));
        } catch (Exception e) {
            log.warnf("SessionKnowledgeBase ingest failed: %s", e.getMessage());
        }
    }

    public List<ScoredChunk> search(String query, int topN) {
        try {
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(topN)
                .build();
            
            EmbeddingSearchResult<TextSegment> result = store.search(request);
            
            return result.matches().stream()
                .map(match -> new ScoredChunk(
                    match.embedded().metadata().getString("id"),
                    match.embedded().text(),
                    match.score()))
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.warnf("SessionKnowledgeBase search failed: %s", e.getMessage());
            return List.of();
        }
    }

    @PreDestroy
    void destroy() {
        try {
            store = new InMemoryEmbeddingStore<>();
            log.debugf("SessionKnowledgeBase wiped on workspace teardown.");
        } catch (Exception e) {
            log.warnf("SessionKnowledgeBase destroy failed: %s", e.getMessage());
        }
    }
}
