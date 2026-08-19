package com.amsha.core.routing;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Helper to discover active Microsoft Foundry Local daemon base URL and runtime state.
 */
public final class FoundryDiscoveryHelper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FoundryDiscoveryHelper() {}

    /**
     * Parses the running Foundry daemon's HTTP API URL from a specified {@code daemon.json} file.
     * Returns the base URL with {@code /v1} suffix (e.g. {@code http://127.0.0.1:51964/v1}) or {@code null} if not found.
     */
    public static String parseDaemonJson(Path daemonJson) {
        if (daemonJson == null || !Files.exists(daemonJson) || !Files.isReadable(daemonJson)) {
            return null;
        }

        try (InputStream is = Files.newInputStream(daemonJson)) {
            JsonNode root = MAPPER.readTree(is);
            JsonNode webUrls = root.path("web_urls");
            if (webUrls.isArray() && !webUrls.isEmpty()) {
                String rawUrl = webUrls.get(0).asText("").trim();
                if (!rawUrl.isBlank()) {
                    while (rawUrl.endsWith("/")) {
                        rawUrl = rawUrl.substring(0, rawUrl.length() - 1);
                    }
                    if (!rawUrl.endsWith("/v1")) {
                        rawUrl = rawUrl + "/v1";
                    }
                    return rawUrl;
                }
            }
        } catch (Exception _) {
            // Ignore parse failures
        }
        return null;
    }

    /**
     * Attempts to discover the running Foundry daemon's HTTP API URL from {@code ~/.foundry/daemon.json}.
     * Returns the base URL with {@code /v1} suffix (e.g. {@code http://127.0.0.1:51964/v1}) or {@code null} if not found.
     */
    public static String discoverDaemonUrl() {
        String userHome = System.getProperty("user.home", "");
        if (userHome.isBlank()) {
            return null;
        }
        return parseDaemonJson(Path.of(userHome, ".foundry", "daemon.json"));
    }

    /**
     * Resolves the base URL, preferring an explicitly provided non-default URL,
     * then falling back to daemon discovery, then the default base URL.
     */
    public static String resolveBaseUrl(String configuredBaseUrl) {
        if (configuredBaseUrl != null
                && !configuredBaseUrl.isBlank()
                && !ModelDefaults.DEFAULT_FOUNDRY_BASE_URL.equalsIgnoreCase(configuredBaseUrl.trim())) {
            return configuredBaseUrl.trim();
        }

        String discovered = discoverDaemonUrl();
        if (discovered != null && !discovered.isBlank()) {
            return discovered;
        }

        if (configuredBaseUrl != null && !configuredBaseUrl.isBlank()) {
            return configuredBaseUrl.trim();
        }
        return ModelDefaults.DEFAULT_FOUNDRY_BASE_URL;
    }
}
