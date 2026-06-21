package com.amsha.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;

public class RagResourcesBuildStep {

    @BuildStep
    ReflectiveClassBuildItem registerRagClasses() {
        return ReflectiveClassBuildItem.builder(
                        "com.amsha.rag.ScoredChunk",
                        "com.amsha.rag.RagContextEnricher",
                        "com.amsha.rag.TwoTierMemoryService",
                        "com.amsha.rag.GlobalKnowledgeBase",
                        "com.amsha.rag.InfiniteLoopEmbeddingModelProvider")
                .methods(true)
                .fields(true)
                .build();
    }
}
