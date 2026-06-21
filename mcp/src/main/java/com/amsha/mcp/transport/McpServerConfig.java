package com.amsha.mcp.transport;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record McpServerConfig(String serverName, String podmanImage, List<String> envVars, Map<String, String> labels) {
    public McpServerConfig {
        Objects.requireNonNull(serverName, "serverName must not be null");
        Objects.requireNonNull(podmanImage, "podmanImage must not be null");
        envVars = envVars == null ? List.of() : List.copyOf(envVars);
        labels = labels == null ? Map.of() : Map.copyOf(labels);
    }
}
