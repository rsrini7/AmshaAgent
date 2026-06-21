package com.amsha.rag.config;

import com.amsha.rag.InfiniteLoopEmbeddingModelProvider;
import com.amsha.rag.RagContextEnricher;
import com.amsha.rag.ScoredChunk;
import com.amsha.rag.TwoTierMemoryService;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(
        targets = {
            ScoredChunk.class,
            RagContextEnricher.class,
            TwoTierMemoryService.class,
            InfiniteLoopEmbeddingModelProvider.class,
        })
public class RagReflectionConfig {}
