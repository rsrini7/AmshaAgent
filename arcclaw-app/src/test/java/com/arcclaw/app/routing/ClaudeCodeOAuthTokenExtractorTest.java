package com.arcclaw.app.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClaudeCodeOAuthTokenExtractorTest {

    private ClaudeCodeOAuthTokenExtractor extractor;
    private ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        extractor = new ClaudeCodeOAuthTokenExtractor();
        extractor.objectMapper = objectMapper;
    }

    @Test
    void testCredentialsFileMissingReturnsEmpty() {
        Path missingPath = tempDir.resolve("non-existent.json");
        Optional<String> result = extractor.extractToken(missingPath);
        assertTrue(result.isEmpty());
    }

    @Test
    void testValidCredentialsReturnsToken() throws IOException {
        Path creds = tempDir.resolve("credentials.json");
        Files.writeString(creds, "{\"accessToken\": \"sk-ant-oauth-123\"}");

        Optional<String> result = extractor.extractToken(creds);
        assertTrue(result.isPresent());
        assertEquals("sk-ant-oauth-123", result.get());
    }

    @Test
    void testFallbackToBearerToken() throws IOException {
        Path creds = tempDir.resolve("credentials.json");
        Files.writeString(creds, "{\"bearerToken\": \"sk-ant-bearer-456\"}");

        Optional<String> result = extractor.extractToken(creds);
        assertTrue(result.isPresent());
        assertEquals("sk-ant-bearer-456", result.get());
    }

    @Test
    void testMalformedJsonReturnsEmpty() throws IOException {
        Path creds = tempDir.resolve("malformed.json");
        Files.writeString(creds, "{ malformed: }");

        Optional<String> result = extractor.extractToken(creds);
        assertTrue(result.isEmpty());
    }

    @Test
    void testTokenFieldMissingReturnsEmpty() throws IOException {
        Path creds = tempDir.resolve("wrong.json");
        Files.writeString(creds, "{\"otherField\": \"data\"}");

        Optional<String> result = extractor.extractToken(creds);
        assertTrue(result.isEmpty());
    }
}
