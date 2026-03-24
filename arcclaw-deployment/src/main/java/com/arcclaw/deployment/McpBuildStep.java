package com.arcclaw.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;

public class McpBuildStep {

    @BuildStep
    ReflectiveClassBuildItem registerMcpReflections(CombinedIndexBuildItem index) {
        // Register all classes in com.arcclaw.mcp package for reflection
        // This is necessary for serialization and CDI discovery in native image.
        String[] classNames = index.getIndex()
            .getKnownClasses().stream()
            .map(ClassInfo::name)
            .map(DotName::toString)
            .filter(name -> name.startsWith("com.arcclaw.mcp"))
            .toArray(String[]::new);

        return ReflectiveClassBuildItem.builder(classNames)
            .methods().fields()
            .build();
    }
}
