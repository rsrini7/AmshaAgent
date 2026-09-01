package com.amsha.rag.config;

import com.amsha.rag.InfiniteLoopEmbeddingModelProvider;
import com.amsha.rag.RagContextEnricher;
import com.amsha.rag.RerankedChunk;
import com.amsha.rag.RerankerPort;
import com.amsha.rag.RerankingService;
import com.amsha.rag.ScoredChunk;
import com.amsha.rag.TwoTierMemoryService;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(
        targets = {
            ScoredChunk.class,
            RerankedChunk.class,
            RerankerPort.class,
            RerankingService.class,
            RagContextEnricher.class,
            TwoTierMemoryService.class,
            InfiniteLoopEmbeddingModelProvider.class,
        })
public class RagReflectionConfig {}
