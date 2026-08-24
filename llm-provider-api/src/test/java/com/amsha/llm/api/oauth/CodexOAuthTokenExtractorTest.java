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
    void extractToken_fromTokensAccessTokenWithoutAccountId() throws IOException {
        String json = """
                {
                  "auth_mode": "chatgpt",
                  "tokens": {
                    "access_token": "token-chatgpt-no-account"
                  }
                }
                """;
        Path authJson = tempDir.resolve("auth_no_acc.json");
        Files.writeString(authJson, json);

        Optional<String> token = extractor.extractTokenFromPath(authJson);
        assertTrue(token.isPresent());
        assertEquals("token-chatgpt-no-account", token.get());
    }

    @Test
    void extractToken_fromTokensAccessTokenWithBlankAccountId() throws IOException {
        String json = """
                {
                  "auth_mode": "chatgpt",
                  "tokens": {
                    "access_token": "token-chatgpt-blank-account",
                    "account_id": "   "
                  }
                }
                """;
        Path authJson = tempDir.resolve("auth_blank_acc.json");
        Files.writeString(authJson, json);

        Optional<String> token = extractor.extractTokenFromPath(authJson);
        assertTrue(token.isPresent());
        assertEquals("token-chatgpt-blank-account", token.get());
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
    void extractToken_fromOpenAiApiKeyWhitespaceTrimmed() throws IOException {
        String json = """
                {
                  "OPENAI_API_KEY": "  sk-proj-whitespace-key  "
                }
                """;
        Path authJson = tempDir.resolve("auth_trim.json");
        Files.writeString(authJson, json);

        Optional<String> token = extractor.extractTokenFromPath(authJson);
        assertTrue(token.isPresent());
        assertEquals("sk-proj-whitespace-key", token.get());
    }

    @Test
    void extractToken_openAiApiKeyNullOrBlankIgnored() throws IOException {
        String jsonNullStr = """
                {
                  "OPENAI_API_KEY": "null"
                }
                """;
        Path f1 = tempDir.resolve("null_str.json");
        Files.writeString(f1, jsonNullStr);
        assertTrue(extractor.extractTokenFromPath(f1).isEmpty());

        String jsonBlank = """
                {
                  "OPENAI_API_KEY": "   "
                }
                """;
        Path f2 = tempDir.resolve("blank.json");
        Files.writeString(f2, jsonBlank);
        assertTrue(extractor.extractTokenFromPath(f2).isEmpty());

        String jsonNull = """
                {
                  "OPENAI_API_KEY": null
                }
                """;
        Path f3 = tempDir.resolve("null_node.json");
        Files.writeString(f3, jsonNull);
        assertTrue(extractor.extractTokenFromPath(f3).isEmpty());
    }

    @Test
    void extractToken_prefersAccessTokenOverOpenAiApiKey() throws IOException {
        String json = """
                {
                  "tokens": {
                    "access_token": "primary-oauth-token"
                  },
                  "OPENAI_API_KEY": "fallback-api-key"
                }
                """;
        Path authJson = tempDir.resolve("both.json");
        Files.writeString(authJson, json);

        Optional<String> token = extractor.extractTokenFromPath(authJson);
        assertTrue(token.isPresent());
        assertEquals("primary-oauth-token", token.get());
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
    void extractToken_nullPath_returnsEmpty() {
        Optional<String> token = extractor.extractTokenFromPath(null);
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
    void extractAccountId_trimmedValue() throws IOException {
        String json = """
                {
                  "tokens": {
                    "account_id": "   acc-trimmed-123   "
                  }
                }
                """;
        Path authJson = tempDir.resolve("auth_acc_trim.json");
        Files.writeString(authJson, json);

        Optional<String> accountId = extractor.extractAccountIdFromPath(authJson);
        assertTrue(accountId.isPresent());
        assertEquals("acc-trimmed-123", accountId.get());
    }

    @Test
    void extractAccountId_missingOrBlank_returnsEmpty() throws IOException {
        Path fEmptyStr = tempDir.resolve("acc_empty.json");
        Files.writeString(fEmptyStr, "{\"tokens\": {\"account_id\": \"\"}}");
        assertTrue(extractor.extractAccountIdFromPath(fEmptyStr).isEmpty());

        Path fBlank = tempDir.resolve("acc_blank.json");
        Files.writeString(fBlank, "{\"tokens\": {\"account_id\": \"   \"}}");
        assertTrue(extractor.extractAccountIdFromPath(fBlank).isEmpty());

        Path fMissing = tempDir.resolve("acc_missing.json");
        Files.writeString(fMissing, "{\"tokens\": {}}");
        assertTrue(extractor.extractAccountIdFromPath(fMissing).isEmpty());

        assertTrue(extractor.extractAccountIdFromPath(null).isEmpty());
        assertTrue(extractor
                .extractAccountIdFromPath(tempDir.resolve("non_existent.json"))
                .isEmpty());
    }

    @Test
    void extractAccountId_corruptedJson_returnsEmpty() throws IOException {
        Path badJson = tempDir.resolve("bad_acc.json");
        Files.writeString(badJson, "{\"tokens\": broken json here...");
        assertTrue(extractor.extractAccountIdFromPath(badJson).isEmpty());
    }

    @Test
    void extractToken_emptyOrMalformedJson_returnsEmpty() throws IOException {
        Path empty = tempDir.resolve("empty.json");
        Files.writeString(empty, "{}");
        Optional<String> token = extractor.extractTokenFromPath(empty);
        assertTrue(token.isEmpty());

        Path corrupted = tempDir.resolve("corrupted.json");
        Files.writeString(corrupted, "{ not valid json");
        assertTrue(extractor.extractTokenFromPath(corrupted).isEmpty());
    }

    @Test
    void extractToken_defaultNoArgMethod() {
        Optional<String> token = extractor.extractToken();
        Optional<String> accountId = extractor.extractAccountId();
        if (token.isPresent()) {
            assertFalse(token.get().isBlank());
        }
        if (accountId.isPresent()) {
            assertFalse(accountId.get().isBlank());
        }
    }
}
