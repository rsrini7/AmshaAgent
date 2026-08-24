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

class AgyOAuthTokenExtractorTest {

    @TempDir
    Path tempDir;

    private AgyOAuthTokenExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new AgyOAuthTokenExtractor();
    }

    @Test
    void extractToken_fromApiKeyField() throws IOException {
        String json = """
                {
                  "apiKey": "AIzaSyTestApiKey12345"
                }
                """;
        Path creds = tempDir.resolve("settings.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractTokenFromPath(creds);
        assertTrue(token.isPresent());
        assertEquals("AIzaSyTestApiKey12345", token.get());
    }

    @Test
    void extractToken_fromAccessTokenField() throws IOException {
        String json = """
                {
                  "accessToken": "ya29.a0AfH6SMTestOAuthToken"
                }
                """;
        Path creds = tempDir.resolve("credentials.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractTokenFromPath(creds);
        assertTrue(token.isPresent());
        assertEquals("ya29.a0AfH6SMTestOAuthToken", token.get());
    }

    @Test
    void extractToken_fromNestedOAuthAccount() throws IOException {
        String json = """
                {
                  "oauthAccount": {
                    "token": "oauth-acc-token-777"
                  }
                }
                """;
        Path creds = tempDir.resolve("settings.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractTokenFromPath(creds);
        assertTrue(token.isPresent());
        assertEquals("oauth-acc-token-777", token.get());
    }

    @Test
    void extractToken_fromNestedGeminiContainer() throws IOException {
        String json = """
                {
                  "gemini": {
                    "api_key": "gemini-container-key-888"
                  }
                }
                """;
        Path creds = tempDir.resolve("settings.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractTokenFromPath(creds);
        assertTrue(token.isPresent());
        assertEquals("gemini-container-key-888", token.get());
    }

    @Test
    void extractToken_fromNestedAntigravityContainer() throws IOException {
        String json = """
                {
                  "antigravity": {
                    "bearerToken": "agy-container-token-999"
                  }
                }
                """;
        Path creds = tempDir.resolve("settings.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractTokenFromPath(creds);
        assertTrue(token.isPresent());
        assertEquals("agy-container-token-999", token.get());
    }

    @Test
    void extractToken_missingFileReturnsEmpty() {
        Path missing = tempDir.resolve("does_not_exist.json");
        Optional<String> token = extractor.extractTokenFromPath(missing);
        assertFalse(token.isPresent());
    }

    @Test
    void extractToken_emptyJsonReturnsEmpty() throws IOException {
        Path creds = tempDir.resolve("empty.json");
        Files.writeString(creds, "{}");

        Optional<String> token = extractor.extractTokenFromPath(creds);
        assertFalse(token.isPresent());
    }

    @Test
    void extractToken_expiredTokenReturnsEmpty() throws IOException {
        String json = """
                {
                  "access_token": "ya29.expired_token_12345",
                  "expiry_date": 1000000000000
                }
                """;
        Path creds = tempDir.resolve("oauth_creds.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractTokenFromPath(creds);
        assertFalse(token.isPresent());
    }

    @Test
    void extractToken_unexpiredTokenReturnsPresent() throws IOException {
        long futureTime = System.currentTimeMillis() + 3600_000L;
        String json = """
                {
                  "access_token": "ya29.valid_future_token_12345",
                  "expiry_date": %d
                }
                """.formatted(futureTime);
        Path creds = tempDir.resolve("oauth_creds.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractTokenFromPath(creds);
        assertTrue(token.isPresent());
        assertEquals("ya29.valid_future_token_12345", token.get());
    }

    @Test
    void extractToken_clientSecretIgnored() throws IOException {
        String json = """
                {
                  "client_secret": "secret_xyz_not_a_token"
                }
                """;
        Path creds = tempDir.resolve("client_secret_only.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractTokenFromPath(creds);
        assertFalse(token.isPresent());
    }
}
