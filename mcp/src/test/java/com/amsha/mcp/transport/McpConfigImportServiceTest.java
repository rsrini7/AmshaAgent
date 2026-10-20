package com.amsha.mcp.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class McpConfigImportServiceTest {
    private final McpConfigImportService service = new McpConfigImportService(new ObjectMapper());

    @Test
    void previewsRemoteEntriesWithoutRetainingSecrets() {
        byte[] content = """
                {"mcpServers":{"tapetide":{"url":"https://example.test/mcp","headers":{"authorization":"Bearer secret"}}}}
                """.getBytes(StandardCharsets.UTF_8);

        McpConfigImportService.ImportPreview preview = service.preview("user-1", ".mcp.json", content);

        assertEquals(".mcp.json", preview.fileName());
        assertEquals(1, preview.entries().size());
        assertTrue(preview.entries().getFirst().supported());
        assertEquals("https://example.test/mcp", preview.entries().getFirst().endpoint());
        assertFalse(preview.entries().getFirst().reason().contains("secret"));
    }

    @Test
    void rejectsStdioAndOversizedUploads() {
        McpConfigImportService.ImportPreview preview =
                service.preview("user-1", "config.json", """
                {"mcpServers":{"local":{"command":"node","args":["server.js"]}}}
                """.getBytes(StandardCharsets.UTF_8));
        assertFalse(preview.entries().getFirst().supported());
        assertEquals("STDIO_NOT_SUPPORTED", preview.entries().getFirst().reason());

        assertThrows(
                IllegalArgumentException.class,
                () -> service.preview("user-1", "config.json", new byte[McpConfigImportService.MAX_UPLOAD_BYTES + 1]));
    }

    @Test
    void applyIsBoundToUserAndConsumesPreviewOnce() {
        McpConfigImportService.ImportPreview preview =
                service.preview("user-1", "config.json", """
                {"mcpServers":{"remote":{"url":"https://example.test/mcp"}}}
                """.getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalArgumentException.class, () -> service.consume("user-2", preview.operationId(), "remote"));
        assertEquals(
                "remote",
                service.consume("user-1", preview.operationId(), "remote").name());
        assertThrows(IllegalArgumentException.class, () -> service.consume("user-1", preview.operationId(), "remote"));
    }

    @Test
    void previewRequiresAUserAndRejectsEmptyOrNonJsonContent() {
        byte[] valid =
                "{\"mcpServers\":{\"r\":{\"url\":\"https://example.test/mcp\"}}}".getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> service.preview(null, "c.json", valid));
        assertThrows(IllegalArgumentException.class, () -> service.preview("   ", "c.json", valid));
        assertThrows(IllegalArgumentException.class, () -> service.preview("user-1", "c.json", null));
        assertThrows(IllegalArgumentException.class, () -> service.preview("user-1", "c.json", new byte[0]));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.preview("user-1", "c.json", "not json".getBytes(StandardCharsets.UTF_8)));
        // Whitespace carries no document at all, so this reaches the parser rather than the length guard.
        assertThrows(
                IllegalArgumentException.class,
                () -> service.preview("user-1", "c.json", "   ".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void previewFallsBackToTheServersKeyAndRejectsAConfigWithNoServerObjects() {
        McpConfigImportService.ImportPreview preview =
                service.preview("user-1", "c.json", """
                {"servers":{"remote":{"url":"https://example.test/mcp"}}}
                """.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, preview.entries().size());
        assertEquals("remote", preview.entries().getFirst().name());

        assertThrows(
                IllegalArgumentException.class,
                () -> service.preview("user-1", "c.json", "{\"mcpServers\":[]}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.preview("user-1", "c.json", "{}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void classifiesEveryUnsupportedEntryShapeWithoutEchoingSecrets() {
        McpConfigImportService.ImportPreview preview =
                service.preview("user-1", "c.json", """
                {"mcpServers":{
                  "": "not-an-object",
                  "env-only": {"env":{"TOKEN":"secret"}},
                  "args-only": {"args":["server.js"]},
                  "no-endpoint": {"transport":"SSE"},
                  "not-http": {"url":"ftp://example.test/mcp"},
                  "plain-http": {"url":"http://example.test/mcp"},
                  "via-endpoint": {"endpoint":"https://example.test/e"}
                }}
                """.getBytes(StandardCharsets.UTF_8));

        Map<String, McpConfigImportService.ImportEntry> byName = preview.entries().stream()
                .collect(Collectors.toMap(McpConfigImportService.ImportEntry::name, entry -> entry));

        assertFalse(byName.get("unnamed").supported());
        assertEquals("MALFORMED_ENTRY", byName.get("unnamed").reason());
        assertEquals("STDIO_NOT_SUPPORTED", byName.get("env-only").reason());
        assertEquals("STDIO_NOT_SUPPORTED", byName.get("args-only").reason());
        assertEquals("ENDPOINT_REQUIRED", byName.get("no-endpoint").reason());
        assertEquals("HTTP_TRANSPORT_REQUIRED", byName.get("not-http").reason());

        McpConfigImportService.ImportEntry plainHttp = byName.get("plain-http");
        assertTrue(plainHttp.supported());
        assertEquals("http://example.test/mcp", plainHttp.endpoint());

        McpConfigImportService.ImportEntry viaEndpoint = byName.get("via-endpoint");
        assertTrue(viaEndpoint.supported());
        assertEquals("https://example.test/e", viaEndpoint.endpoint());
        assertEquals("HTTP_SSE", viaEndpoint.transport());
        assertTrue(preview.entries().stream().noneMatch(entry -> entry.reason().contains("secret")));
    }

    @Test
    void safeFileNameStripsDirectoriesAndFallsBackForBlankOrOverlongNames() {
        byte[] content =
                "{\"mcpServers\":{\"r\":{\"url\":\"https://example.test/mcp\"}}}".getBytes(StandardCharsets.UTF_8);

        assertEquals("mcp.json", service.preview("user-1", null, content).fileName());
        assertEquals("mcp.json", service.preview("user-1", "   ", content).fileName());
        assertEquals(
                "c.json", service.preview("user-1", "/tmp/dir/c.json", content).fileName());
        assertEquals(
                "c.json", service.preview("user-1", "C:\\dir\\c.json", content).fileName());
        assertEquals("mcp.json", service.preview("user-1", "/", content).fileName());
        assertEquals(
                "mcp.json", service.preview("user-1", "x".repeat(257), content).fileName());
    }

    @Test
    void consumeRejectsBlankUnknownAndMissingUser() {
        assertThrows(IllegalArgumentException.class, () -> service.consume(null, "op", "remote"));
        assertThrows(IllegalArgumentException.class, () -> service.consume("user-1", null, "remote"));
        assertThrows(IllegalArgumentException.class, () -> service.consume("user-1", "  ", "remote"));
        assertThrows(IllegalArgumentException.class, () -> service.consume("user-1", "no-such-op", "remote"));
    }

    @Test
    void consumeRejectsAServerOutsideThePreviewWithoutConsumingIt() {
        McpConfigImportService.ImportPreview preview =
                service.preview("user-1", "c.json", """
                {"mcpServers":{"remote":{"url":"https://example.test/mcp"}}}
                """.getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.consume("user-1", preview.operationId(), "not-in-preview"));
        assertTrue(error.getMessage().contains("not in the preview"));

        // A rejected name must leave the preview intact so the real entry can still be applied.
        assertEquals(
                "remote",
                service.consume("user-1", preview.operationId(), "remote").name());
    }

    @Test
    void sweepExpiredDropsAStalePreviewBeforeItCanBeConsumed() throws Exception {
        // White-box: the preview TTL is ten minutes, so plant an already-stale entry to exercise the sweep.
        Class<?> pendingType = Class.forName("com.amsha.mcp.transport.McpConfigImportService$PendingPreview");
        var constructor = pendingType.getDeclaredConstructor(String.class, List.class, Instant.class);
        constructor.setAccessible(true);
        Object stale =
                constructor.newInstance("user-1", List.of(), Instant.now().minusSeconds(60));

        Field previewsField = McpConfigImportService.class.getDeclaredField("previews");
        previewsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> previews = (Map<String, Object>) previewsField.get(service);
        previews.put("stale-op", stale);

        assertThrows(IllegalArgumentException.class, () -> service.consume("user-1", "stale-op", "remote"));
        assertFalse(previews.containsKey("stale-op"));
    }
}
