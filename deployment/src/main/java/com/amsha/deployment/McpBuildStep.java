package com.amsha.deployment;

import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;

public class McpBuildStep {

    @BuildStep
    ReflectiveClassBuildItem registerMcpReflections(CombinedIndexBuildItem index) {
        // Register classes needed for JSON serialization and dynamic tool dispatch.
        // DTOs are covered by McpReflectionConfig — this covers the remaining
        // transport/bridge infrastructure that Quarkus may not detect automatically.
        String[] classNames = index.getIndex().getKnownClasses().stream()
                .map(ClassInfo::name)
                .map(DotName::toString)
                .filter(name -> name.startsWith("com.amsha.mcp.transport") || name.startsWith("com.amsha.mcp.bridge"))
                .toArray(String[]::new);
        return ReflectiveClassBuildItem.builder(classNames).methods().fields().build();
    }
}
