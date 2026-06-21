package com.arcclaw.rag.config;

import com.arcclaw.rag.InfiniteLoopEmbeddingModelProvider;
import com.arcclaw.rag.RagContextEnricher;
import com.arcclaw.rag.ScoredChunk;
import com.arcclaw.rag.TwoTierMemoryService;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(
        targets = {
            ScoredChunk.class,
            RagContextEnricher.class,
            TwoTierMemoryService.class,
            InfiniteLoopEmbeddingModelProvider.class,
        })
public class RagReflectionConfig {}
