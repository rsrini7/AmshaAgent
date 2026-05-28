package com.arcclaw.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;

public class RagResourcesBuildStep {

    @BuildStep
    ReflectiveClassBuildItem registerRagClasses() {
        return ReflectiveClassBuildItem.builder(
                        "com.arcclaw.rag.ScoredChunk",
                        "com.arcclaw.rag.RagContextEnricher",
                        "com.arcclaw.rag.TwoTierMemoryService",
                        "com.arcclaw.rag.GlobalKnowledgeBase",
                        "com.arcclaw.rag.EmbeddingModelProducer")
                .methods(true)
                .fields(true)
                .build();
    }
}
