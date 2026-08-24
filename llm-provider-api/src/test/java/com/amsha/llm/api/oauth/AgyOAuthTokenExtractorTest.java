package com.amsha.llm.api.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    void extractToken_fromApiUnderscoreKeyField() throws IOException {
        String json = """
                {
                  "api_key": "AIzaSyTestApiKey_Underscore"
                }
                """;
        Path creds = tempDir.resolve("creds_api_key.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractToken(creds);
        assertTrue(token.isPresent());
        assertEquals("AIzaSyTestApiKey_Underscore", token.get());
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
    void extractToken_fromAccessUnderscoreTokenField() throws IOException {
        String json = """
                {
                  "access_token": "ya29.a0AfH6SMTestOAuthToken_Underscore"
                }
                """;
        Path creds = tempDir.resolve("credentials.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractTokenFromPath(creds);
        assertTrue(token.isPresent());
        assertEquals("ya29.a0AfH6SMTestOAuthToken_Underscore", token.get());
    }

    @Test
    void extractToken_fromBearerTokenField() throws IOException {
        String json = """
                {
                  "bearerToken": "bearer_token_val_111"
                }
                """;
        Path creds = tempDir.resolve("bearer.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractTokenFromPath(creds);
        assertTrue(token.isPresent());
        assertEquals("bearer_token_val_111", token.get());
    }

    @Test
    void extractToken_fromTokenField() throws IOException {
        String json = """
                {
                  "token": "token_val_222"
                }
                """;
        Path creds = tempDir.resolve("token.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractTokenFromPath(creds);
        assertTrue(token.isPresent());
        assertEquals("token_val_222", token.get());
    }

    @Test
    void extractToken_fromOauthTokenField() throws IOException {
        String json = """
                {
                  "oauthToken": "oauth_token_val_333"
                }
                """;
        Path creds = tempDir.resolve("oauthToken.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractTokenFromPath(creds);
        assertTrue(token.isPresent());
        assertEquals("oauth_token_val_333", token.get());
    }

    @Test
    void extractToken_fromSessionKeyField() throws IOException {
        String json = """
                {
                  "sessionKey": "session_key_val_444"
                }
                """;
        Path creds = tempDir.resolve("sessionKey.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractTokenFromPath(creds);
        assertTrue(token.isPresent());
        assertEquals("session_key_val_444", token.get());
    }

    @Test
    void extractToken_fromNestedContainers() throws IOException {
        String[] containers = {"oauthAccount", "oauth", "credentials", "tokens", "auth", "gemini", "antigravity"};
        for (String container : containers) {
            String json = "{\"" + container + "\": {\"apiKey\": \"key-in-" + container + "\"}}";
            Path creds = tempDir.resolve("container_" + container + ".json");
            Files.writeString(creds, json);

            Optional<String> token = extractor.extractTokenFromPath(creds);
            assertTrue(token.isPresent(), "Expected token in container: " + container);
            assertEquals("key-in-" + container, token.get());
        }
    }

    @Test
    void extractToken_fromNestedContainerWithNonObjectValue() throws IOException {
        String json = """
                {
                  "oauthAccount": "not_an_object",
                  "apiKey": "top_level_fallback"
                }
                """;
        Path creds = tempDir.resolve("nested_non_object.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractTokenFromPath(creds);
        assertTrue(token.isPresent());
        assertEquals("top_level_fallback", token.get());
    }

    @Test
    void extractToken_fromNestedContainerWithArrayValue() throws IOException {
        String json = """
                {
                  "credentials": ["token1", "token2"],
                  "token": "top_level_token"
                }
                """;
        Path creds = tempDir.resolve("nested_array.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractTokenFromPath(creds);
        assertTrue(token.isPresent());
        assertEquals("top_level_token", token.get());
    }

    @Test
    void extractToken_nestedContainerWithBlankValueIgnored() throws IOException {
        String json = """
                {
                  "tokens": {
                    "apiKey": "   "
                  }
                }
                """;
        Path creds = tempDir.resolve("nested_blank.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractTokenFromPath(creds);
        assertFalse(token.isPresent());
    }

    @Test
    void extractToken_missingFileReturnsEmpty() {
        Path missing = tempDir.resolve("does_not_exist.json");
        Optional<String> token = extractor.extractTokenFromPath(missing);
        assertFalse(token.isPresent());
    }

    @Test
    void extractToken_nullPathReturnsEmpty() {
        Optional<String> token = extractor.extractTokenFromPath(null);
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
    void extractToken_corruptedJsonReturnsEmpty() throws IOException {
        Path badJson = tempDir.resolve("corrupted.json");
        Files.writeString(badJson, "{\"apiKey\": not valid json here");

        Optional<String> token = extractor.extractTokenFromPath(badJson);
        assertFalse(token.isPresent());
    }

    @Test
    void extractToken_nonObjectJsonRoots() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        JsonNode arrayNode = mapper.readTree("[\"some_token\", \"another\"]");
        assertTrue(extractor.extractTokenFromJson(arrayNode).isEmpty());

        JsonNode textNode = mapper.readTree("\"plain_string_token\"");
        assertTrue(extractor.extractTokenFromJson(textNode).isEmpty());

        JsonNode numberNode = mapper.readTree("12345");
        assertTrue(extractor.extractTokenFromJson(numberNode).isEmpty());

        JsonNode booleanNode = mapper.readTree("true");
        assertTrue(extractor.extractTokenFromJson(booleanNode).isEmpty());

        assertTrue(extractor.extractTokenFromJson(null).isEmpty());
    }

    @Test
    void extractToken_blankAndWhitespaceValuesIgnored() throws IOException {
        String json = """
                {
                  "apiKey": "   ",
                  "access_token": "\\t\\n"
                }
                """;
        Path creds = tempDir.resolve("blank.json");
        Files.writeString(creds, json);

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
        Path creds = tempDir.resolve("oauth_creds_ms.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractTokenFromPath(creds);
        assertFalse(token.isPresent());

        long pastSeconds = (System.currentTimeMillis() / 1000) - 1000;
        String jsonSec = """
                {
                  "access_token": "ya29.expired_token_sec",
                  "expiry_date": %d
                }
                """.formatted(pastSeconds);
        Path credsSec = tempDir.resolve("oauth_creds_sec.json");
        Files.writeString(credsSec, jsonSec);

        Optional<String> tokenSec = extractor.extractTokenFromPath(credsSec);
        assertFalse(tokenSec.isPresent());
    }

    @Test
    void extractToken_unexpiredTokenReturnsPresent() throws IOException {
        long futureMs = System.currentTimeMillis() + 3600_000L;
        String jsonMs = """
                {
                  "access_token": "ya29.valid_future_token_ms",
                  "expiry_date": %d
                }
                """.formatted(futureMs);
        Path credsMs = tempDir.resolve("oauth_creds_ms.json");
        Files.writeString(credsMs, jsonMs);

        Optional<String> tokenMs = extractor.extractTokenFromPath(credsMs);
        assertTrue(tokenMs.isPresent());
        assertEquals("ya29.valid_future_token_ms", tokenMs.get());
    }

    @Test
    void extractToken_expiryZeroOrNegativeIgnored() throws IOException {
        String json = """
                {
                  "access_token": "ya29.token_with_zero_expiry",
                  "expiry_date": 0
                }
                """;
        Path creds = tempDir.resolve("zero_expiry.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractTokenFromPath(creds);
        assertTrue(token.isPresent());
        assertEquals("ya29.token_with_zero_expiry", token.get());
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

    @Test
    void extractToken_fallbackToSystemProperty() {
        String originalProp = System.getProperty("amsha.llm.agy.api-key");
        String originalOs = System.getProperty("os.name");
        String originalHome = System.getProperty("user.home");
        try {
            System.setProperty("os.name", "Linux");
            System.setProperty("user.home", tempDir.resolve("empty_home").toString());
            System.setProperty("amsha.llm.agy.api-key", "system-prop-agy-key-123");

            Optional<String> token = extractor.extractToken();
            assertTrue(token.isPresent());
            assertEquals("system-prop-agy-key-123", token.get());
        } finally {
            if (originalProp != null) {
                System.setProperty("amsha.llm.agy.api-key", originalProp);
            } else {
                System.clearProperty("amsha.llm.agy.api-key");
            }
            if (originalOs != null) {
                System.setProperty("os.name", originalOs);
            }
            if (originalHome != null) {
                System.setProperty("user.home", originalHome);
            }
        }
    }

    @Test
    void extractToken_allCandidateFilesystemPaths() throws IOException {
        String origOs = System.getProperty("os.name");
        String origHome = System.getProperty("user.home");
        String origProp = System.getProperty("amsha.llm.agy.api-key");
        try {
            System.setProperty("os.name", "Linux");
            System.clearProperty("amsha.llm.agy.api-key");

            String[][] relPaths = {
                {".gemini", "antigravity-cli", "settings.json"},
                {".gemini", "antigravity-cli", "credentials.json"},
                {".gemini", "credentials.json"},
                {".gemini", "oauth_creds.json"},
                {".gemini", "settings.json"}
            };

            for (int i = 0; i < relPaths.length; i++) {
                Path mockHome = tempDir.resolve("mock_home_" + i);
                Path target = mockHome;
                for (String part : relPaths[i]) {
                    target = target.resolve(part);
                }
                Files.createDirectories(target.getParent());
                Files.writeString(target, "{\"apiKey\": \"cand-key-" + i + "\"}");
                System.setProperty("user.home", mockHome.toString());

                Optional<String> token = extractor.extractToken();
                assertTrue(token.isPresent(), "Expected token for candidate path index " + i);
                assertEquals("cand-key-" + i, token.get());
            }
        } finally {
            if (origProp != null) {
                System.setProperty("amsha.llm.agy.api-key", origProp);
            }
            if (origOs != null) {
                System.setProperty("os.name", origOs);
            }
            if (origHome != null) {
                System.setProperty("user.home", origHome);
            }
        }
    }

    @Test
    void extractToken_macKeychainLookup() {
        String origOs = System.getProperty("os.name");
        String origHome = System.getProperty("user.home");
        String origProp = System.getProperty("amsha.llm.agy.api-key");
        try {
            System.setProperty("os.name", "Mac OS X");
            System.setProperty(
                    "user.home", tempDir.resolve("empty_home_keychain").toString());
            System.clearProperty("amsha.llm.agy.api-key");

            extractor.extractToken();
        } finally {
            if (origProp != null) {
                System.setProperty("amsha.llm.agy.api-key", origProp);
            }
            if (origOs != null) {
                System.setProperty("os.name", origOs);
            }
            if (origHome != null) {
                System.setProperty("user.home", origHome);
            }
        }
    }

    @Test
    void extractToken_emptyUserHomeReturnsEmpty() {
        String originalOs = System.getProperty("os.name");
        String originalHome = System.getProperty("user.home");
        String originalProp = System.getProperty("amsha.llm.agy.api-key");
        try {
            System.setProperty("os.name", "Linux");
            System.clearProperty("amsha.llm.agy.api-key");
            System.setProperty("user.home", "");

            Optional<String> token = extractor.extractToken();
            assertFalse(token.isPresent() && token.get().equals("agy-home-candidate-key"));
        } finally {
            if (originalProp != null) {
                System.setProperty("amsha.llm.agy.api-key", originalProp);
            }
            if (originalOs != null) {
                System.setProperty("os.name", originalOs);
            }
            if (originalHome != null) {
                System.setProperty("user.home", originalHome);
            }
        }
    }
}
