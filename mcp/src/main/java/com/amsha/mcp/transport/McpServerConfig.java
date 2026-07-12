package com.amsha.mcp.transport;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.amsha.core.tools.ToolCategory;

public record McpServerConfig(
        String serverName,
        String podmanImage,
        List<String> envVars,
        Map<String, String> labels,
        ToolCategory category) {
    public McpServerConfig(String serverName, String podmanImage, List<String> envVars, Map<String, String> labels) {
        this(serverName, podmanImage, envVars, labels, ToolCategory.NETWORK);
    }

    public McpServerConfig {
        Objects.requireNonNull(serverName, "serverName must not be null");
        Objects.requireNonNull(podmanImage, "podmanImage must not be null");
        Objects.requireNonNull(category, "category must not be null");
        envVars = envVars == null ? List.of() : List.copyOf(envVars);
        labels = labels == null ? Map.of() : Map.copyOf(labels);
    }
}
