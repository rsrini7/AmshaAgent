package com.arcclaw.app.config;

import com.arcclaw.app.channel.telegram.TelegramAgentHandler;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(
    targets = {
        TelegramAgentHandler.class
    },
    classNames = {
        "com.arcclaw.rag.ScoredChunk",
        "com.arcclaw.rag.RagContextEnricher",
        "com.arcclaw.rag.TwoTierMemoryService",
        "com.arcclaw.rag.GlobalKnowledgeBase",
        "com.arcclaw.rag.EmbeddingModelProducer"
    }
)
public class AppReflectionConfig {
}