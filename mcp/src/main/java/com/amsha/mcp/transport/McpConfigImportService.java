package com.amsha.mcp.transport;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Bounded, local-only parser for user-uploaded remote MCP configuration files. */
@ApplicationScoped
public class McpConfigImportService {
    public static final int MAX_UPLOAD_BYTES = 256 * 1024;
    private static final Duration PREVIEW_TTL = Duration.ofMinutes(10);

    private final ObjectMapper objectMapper;
    private final Map<String, PendingPreview> previews = new ConcurrentHashMap<>();

    @Inject
    public McpConfigImportService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ImportPreview preview(String userId, String fileName, byte[] content) {
        requireUser(userId);
        sweepExpired();
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("MCP config upload is empty");
        }
        if (content.length > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("MCP config upload exceeds 256 KiB");
        }
        String safeFileName = safeFileName(fileName);
        JsonNode root = parse(content);
        List<ImportEntry> entries = parseEntries(root);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("MCP config contains no server entries");
        }
        String operationId = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plus(PREVIEW_TTL);
        previews.put(operationId, new PendingPreview(userId, entries, expiresAt));
        return new ImportPreview(operationId, safeFileName, entries, expiresAt);
    }

    public ImportEntry consume(String userId, String operationId, String serverName) {
        requireUser(userId);
        sweepExpired();
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("MCP import operation is required");
        }
        PendingPreview preview = previews.get(operationId);
        if (preview == null
                || !userId.equals(preview.userId())
                || preview.expiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("MCP import preview is unknown or expired");
        }
        ImportEntry entry = preview.entries().stream()
                .filter(candidate -> candidate.name().equals(serverName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("MCP import server is not in the preview"));
        if (!previews.remove(operationId, preview)) {
            throw new IllegalArgumentException("MCP import preview was already consumed");
        }
        return entry;
    }

    private JsonNode parse(byte[] content) {
        try {
            JsonNode root = objectMapper.readTree(new String(content, StandardCharsets.UTF_8));
            if (root == null) throw new IllegalArgumentException("MCP config is empty");
            return root;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("MCP config is not valid JSON", exception);
        }
    }

    private static List<ImportEntry> parseEntries(JsonNode root) {
        JsonNode servers = root.path("mcpServers");
        if (!servers.isObject()) {
            servers = root.path("servers");
        }
        if (!servers.isObject()) {
            return List.of();
        }
        List<ImportEntry> entries = new ArrayList<>();
        servers.properties().forEach(field -> entries.add(parseEntry(field.getKey(), field.getValue())));
        return List.copyOf(entries);
    }

    private static ImportEntry parseEntry(String name, JsonNode definition) {
        String safeName = name == null || name.isBlank() ? "unnamed" : name.trim();
        if (!definition.isObject()) {
            return new ImportEntry(safeName, "", "", false, "MALFORMED_ENTRY");
        }
        if (definition.has("command") || definition.has("args") || definition.has("env")) {
            return new ImportEntry(safeName, "", "STDIO", false, "STDIO_NOT_SUPPORTED");
        }
        String endpoint = firstText(definition, "url", "endpoint");
        String transport = definition.path("transport").asText("HTTP_SSE");
        if (endpoint.isBlank()) {
            return new ImportEntry(safeName, "", transport, false, "ENDPOINT_REQUIRED");
        }
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            return new ImportEntry(safeName, endpoint, transport, false, "HTTP_TRANSPORT_REQUIRED");
        }
        return new ImportEntry(safeName, endpoint, transport, true, "");
    }

    private static String firstText(JsonNode node, String... names) {
        for (String name : names) {
            String value = node.path(name).asText("").trim();
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static String safeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) return "mcp.json";
        String normalized = fileName.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String baseName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        if (baseName.isBlank() || baseName.length() > 256) return "mcp.json";
        return baseName;
    }

    private void sweepExpired() {
        Instant now = Instant.now();
        previews.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private static void requireUser(String userId) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("MCP user is required");
    }

    private record PendingPreview(String userId, List<ImportEntry> entries, Instant expiresAt) {}

    public record ImportPreview(String operationId, String fileName, List<ImportEntry> entries, Instant expiresAt) {}

    public record ImportEntry(String name, String endpoint, String transport, boolean supported, String reason) {}
}
