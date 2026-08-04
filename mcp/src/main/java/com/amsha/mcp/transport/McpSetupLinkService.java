package com.amsha.mcp.transport;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Builds channel-neutral links to the authenticated MCP setup panel. */
@ApplicationScoped
public class McpSetupLinkService {
    private final String uiBaseUrl;

    public McpSetupLinkService(
            @ConfigProperty(name = "amsha.mcp.setup-ui-base-url", defaultValue = "") String uiBaseUrl) {
        this.uiBaseUrl = trimTrailingSlashes(uiBaseUrl);
    }

    public String link(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("MCP setup token is required");
        }
        return uiBaseUrl + "/#/mcp/setup?token=" + token;
    }

    private static String trimTrailingSlashes(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
