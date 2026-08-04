package com.amsha.mcp.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

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
}
