package com.arcclaw.app.config;

import com.arcclaw.app.channel.telegram.TelegramAgentHandler;
import com.arcclaw.rag.EmbeddingModelProducer;
import com.arcclaw.rag.GlobalKnowledgeBase;
import com.arcclaw.rag.RagContextEnricher;
import com.arcclaw.rag.ScoredChunk;
import com.arcclaw.rag.TwoTierMemoryService;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(
    targets = {
        TelegramAgentHandler.class,
        ScoredChunk.class,
        RagContextEnricher.class,
        TwoTierMemoryService.class,
        GlobalKnowledgeBase.class,
        EmbeddingModelProducer.class
    }
)
public class AppReflectionConfig {
}