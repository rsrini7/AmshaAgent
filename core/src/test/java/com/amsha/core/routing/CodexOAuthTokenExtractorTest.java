package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodexOAuthTokenExtractorTest {

    @TempDir
    Path tempDir;

    private CodexOAuthTokenExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new CodexOAuthTokenExtractor();
    }

    @Test
    void extractToken_fromTokensAccessTokenWithAccountId() throws IOException {
        String json = """
                {
                  "auth_mode": "chatgpt",
                  "tokens": {
                    "access_token": "token-chatgpt-12345",
                    "account_id": "acc-abc-xyz"
                  }
                }
                """;
        Path authJson = tempDir.resolve("auth.json");
        Files.writeString(authJson, json);

        Optional<String> token = extractor.extractTokenFromPath(authJson);
        assertTrue(token.isPresent());
        assertEquals("token-chatgpt-12345", token.get());
    }

    @Test
    void extractToken_fromOpenAiApiKeyFallback() throws IOException {
        String json = """
                {
                  "auth_mode": "api_key",
                  "OPENAI_API_KEY": "sk-proj-test-key-999"
                }
                """;
        Path authJson = tempDir.resolve("auth.json");
        Files.writeString(authJson, json);

        Optional<String> token = extractor.extractTokenFromPath(authJson);
        assertTrue(token.isPresent());
        assertEquals("sk-proj-test-key-999", token.get());
    }

    @Test
    void extractToken_doesNotPromoteIdTokenToBearerCredential() throws IOException {
        String json = """
                {
                  "auth_mode": "chatgpt",
                  "tokens": {
                    "id_token": "id-token-67890"
                  }
                }
                """;
        Path authJson = tempDir.resolve("auth.json");
        Files.writeString(authJson, json);

        Optional<String> token = extractor.extractTokenFromPath(authJson);
        assertTrue(token.isEmpty(), "OIDC id_token must not be promoted to API bearer credential");
    }

    @Test
    void extractToken_fileNotFound_returnsEmpty() {
        Path missing = tempDir.resolve("non-existent-auth.json");
        Optional<String> token = extractor.extractTokenFromPath(missing);
        assertTrue(token.isEmpty());
    }

    @Test
    void extractAccountId_fromTokensAccountId() throws IOException {
        String json = """
                {
                  "auth_mode": "chatgpt",
                  "tokens": {
                    "access_token": "token-chatgpt-12345",
                    "account_id": "acc-abc-xyz"
                  }
                }
                """;
        Path authJson = tempDir.resolve("auth.json");
        Files.writeString(authJson, json);

        Optional<String> accountId = extractor.extractAccountIdFromPath(authJson);
        assertTrue(accountId.isPresent());
        assertEquals("acc-abc-xyz", accountId.get());
    }

    @Test
    void extractToken_emptyOrMalformedJson_returnsEmpty() throws IOException {
        Path empty = tempDir.resolve("empty.json");
        Files.writeString(empty, "{}");
        Optional<String> token = extractor.extractTokenFromPath(empty);
        assertTrue(token.isEmpty());
    }
}
