package com.amsha.llm.api.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClaudeCodeOAuthTokenExtractorTest {

    @TempDir
    Path tempDir;

    private ClaudeCodeOAuthTokenExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new ClaudeCodeOAuthTokenExtractor();
    }

    @Test
    void extractToken_fromAccessTokenField() throws IOException {
        String json = """
                {
                  "accessToken": "sk-ant-oauth-token-12345",
                  "refreshToken": "ref-67890"
                }
                """;
        Path creds = tempDir.resolve(".credentials.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractTokenFromPath(creds);
        assertTrue(token.isPresent());
        assertEquals("sk-ant-oauth-token-12345", token.get());
    }

    @Test
    void extractToken_fromBearerTokenField() throws IOException {
        String json = """
                {
                  "bearerToken": "bearer-token-abcde"
                }
                """;
        Path creds = tempDir.resolve("credentials.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractTokenFromPath(creds);
        assertTrue(token.isPresent());
        assertEquals("bearer-token-abcde", token.get());
    }

    @Test
    void extractToken_fromApiKeyField() throws IOException {
        String json = """
                {
                  "apiKey": "sk-ant-api03-test-key"
                }
                """;
        Path creds = tempDir.resolve(".claude.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractTokenFromPath(creds);
        assertTrue(token.isPresent());
        assertEquals("sk-ant-api03-test-key", token.get());
    }

    @Test
    void extractToken_fromNestedTokensObject() throws IOException {
        String json = """
                {
                  "tokens": {
                    "access_token": "nested-token-999"
                  }
                }
                """;
        Path creds = tempDir.resolve(".credentials.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractTokenFromPath(creds);
        assertTrue(token.isPresent());
        assertEquals("nested-token-999", token.get());
    }

    @Test
    void extractToken_fromNestedClaudeObject() throws IOException {
        String json = """
                {
                  "claude": {
                    "accessToken": "nested-claude-token-555"
                  }
                }
                """;
        Path creds = tempDir.resolve(".credentials.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractTokenFromPath(creds);
        assertTrue(token.isPresent());
        assertEquals("nested-claude-token-555", token.get());
    }

    @Test
    void extractToken_fromRealClaudeAiOauthStructure() throws IOException {
        String json = """
                {
                  "claudeAiOauth": {
                    "accessToken": "sk-ant-oauth-real-claude-cli-token-12345",
                    "refreshToken": "sk-ant-refresh-token",
                    "expiresAt": 1740000000000
                  }
                }
                """;
        Path creds = tempDir.resolve(".credentials.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractTokenFromPath(creds);
        assertTrue(token.isPresent());
        assertEquals("sk-ant-oauth-real-claude-cli-token-12345", token.get());
    }

    @Test
    void extractToken_fromOauthAccountStructure() throws IOException {
        String json = """
                {
                  "oauthAccount": {
                    "accessToken": "sk-ant-oauth-account-token-777",
                    "accountUuid": "acc-123"
                  }
                }
                """;
        Path creds = tempDir.resolve(".claude.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractTokenFromPath(creds);
        assertTrue(token.isPresent());
        assertEquals("sk-ant-oauth-account-token-777", token.get());
    }

    @Test
    void extractToken_nonExistentPath_returnsEmpty() {
        Optional<String> token = extractor.extractTokenFromPath(tempDir.resolve("missing.json"));
        assertTrue(token.isEmpty());
    }

    @Test
    void extractToken_invalidJson_returnsEmpty() throws IOException {
        Path badJson = tempDir.resolve("bad.json");
        Files.writeString(badJson, "{ invalid-json ");
        Optional<String> token = extractor.extractTokenFromPath(badJson);
        assertTrue(token.isEmpty());
    }
}
