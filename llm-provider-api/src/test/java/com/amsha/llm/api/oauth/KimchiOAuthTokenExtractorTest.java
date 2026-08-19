package com.amsha.llm.api.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KimchiOAuthTokenExtractorTest {

    @TempDir
    Path tempDir;

    private KimchiOAuthTokenExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new KimchiOAuthTokenExtractor();
    }

    @Test
    void testExtractFromDirectApiKeyField() throws IOException {
        Path configPath = tempDir.resolve("config.json");
        Files.writeString(configPath, "{\"apiKey\": \"kimchi_test_key_123\"}");

        Optional<String> token = extractor.extractToken(configPath);
        assertTrue(token.isPresent());
        assertEquals("kimchi_test_key_123", token.get());
    }

    @Test
    void testExtractFromSnakeCaseApiKeyField() throws IOException {
        Path configPath = tempDir.resolve("config.json");
        Files.writeString(configPath, "{\"api_key\": \"kimchi_snake_key_456\"}");

        Optional<String> token = extractor.extractToken(configPath);
        assertTrue(token.isPresent());
        assertEquals("kimchi_snake_key_456", token.get());
    }

    @Test
    void testExtractFromTokenField() throws IOException {
        Path configPath = tempDir.resolve("config.json");
        Files.writeString(configPath, "{\"token\": \"kimchi_token_789\"}");

        Optional<String> token = extractor.extractToken(configPath);
        assertTrue(token.isPresent());
        assertEquals("kimchi_token_789", token.get());
    }

    @Test
    void testExtractFromAccessTokenField() throws IOException {
        Path configPath = tempDir.resolve("config.json");
        Files.writeString(configPath, "{\"accessToken\": \"kimchi_access_token_abc\"}");

        Optional<String> token = extractor.extractToken(configPath);
        assertTrue(token.isPresent());
        assertEquals("kimchi_access_token_abc", token.get());
    }

    @Test
    void testExtractFromOpenCodeStructure() throws IOException {
        Path configPath = tempDir.resolve("opencode.json");
        Files.writeString(
                configPath, "{\"provider\": {\"kimchi\": {\"options\": {\"apiKey\": \"opencode_kimchi_key_xyz\"}}}}");

        Optional<String> token = extractor.extractToken(configPath);
        assertTrue(token.isPresent());
        assertEquals("opencode_kimchi_key_xyz", token.get());
    }

    @Test
    void testExtractFromNestedAuthObject() throws IOException {
        Path configPath = tempDir.resolve("config.json");
        Files.writeString(configPath, "{\"auth\": {\"bearerToken\": \"nested_bearer_token_111\"}}");

        Optional<String> token = extractor.extractToken(configPath);
        assertTrue(token.isPresent());
        assertEquals("nested_bearer_token_111", token.get());
    }

    @Test
    void testMissingFileReturnsEmpty() {
        Path missing = tempDir.resolve("non_existent.json");
        Optional<String> token = extractor.extractToken(missing);
        assertFalse(token.isPresent());
    }

    @Test
    void testInvalidJsonReturnsEmpty() throws IOException {
        Path invalid = tempDir.resolve("invalid.json");
        Files.writeString(invalid, "not-valid-json-{{{");

        Optional<String> token = extractor.extractToken(invalid);
        assertFalse(token.isPresent());
    }

    @Test
    void testEmptyJsonReturnsEmpty() throws IOException {
        Path empty = tempDir.resolve("empty.json");
        Files.writeString(empty, "{}");

        Optional<String> token = extractor.extractToken(empty);
        assertFalse(token.isPresent());
    }

    @Test
    void testBlankKeyReturnsEmpty() throws IOException {
        Path blank = tempDir.resolve("blank.json");
        Files.writeString(blank, "{\"apiKey\": \"   \"}");

        Optional<String> token = extractor.extractToken(blank);
        assertFalse(token.isPresent());
    }
}
