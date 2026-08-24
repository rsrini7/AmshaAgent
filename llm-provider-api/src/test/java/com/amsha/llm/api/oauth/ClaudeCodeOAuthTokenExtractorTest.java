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
    void extractToken_fromAccessUnderscoreTokenField() throws IOException {
        String json = """
                {
                  "access_token": "sk-ant-access-underscore-token"
                }
                """;
        Path creds = tempDir.resolve("creds.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractToken(creds);
        assertTrue(token.isPresent());
        assertEquals("sk-ant-access-underscore-token", token.get());
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
    void extractToken_fromTokenField() throws IOException {
        String json = """
                {
                  "token": "direct-token-field-val"
                }
                """;
        Path creds = tempDir.resolve("token.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractTokenFromPath(creds);
        assertTrue(token.isPresent());
        assertEquals("direct-token-field-val", token.get());
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
    void extractToken_fromApiUnderscoreKeyField() throws IOException {
        String json = """
                {
                  "api_key": "sk-ant-api-underscore-key"
                }
                """;
        Path creds = tempDir.resolve("api_key.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractTokenFromPath(creds);
        assertTrue(token.isPresent());
        assertEquals("sk-ant-api-underscore-key", token.get());
    }

    @Test
    void extractToken_fromOauthTokenField() throws IOException {
        String json = """
                {
                  "oauthToken": "sk-ant-oauth-field-val"
                }
                """;
        Path creds = tempDir.resolve("oauth_tok.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractTokenFromPath(creds);
        assertTrue(token.isPresent());
        assertEquals("sk-ant-oauth-field-val", token.get());
    }

    @Test
    void extractToken_fromSessionKeyField() throws IOException {
        String json = """
                {
                  "sessionKey": "sk-ant-session-key-val"
                }
                """;
        Path creds = tempDir.resolve("session_key.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractTokenFromPath(creds);
        assertTrue(token.isPresent());
        assertEquals("sk-ant-session-key-val", token.get());
    }

    @Test
    void extractToken_fromNestedContainers() throws IOException {
        String[] containers = {"claudeAiOauth", "oauthAccount", "oauth", "tokens", "claude"};
        for (String container : containers) {
            String json = "{\"" + container + "\": {\"accessToken\": \"nested-token-in-" + container + "\"}}";
            Path creds = tempDir.resolve("container_" + container + ".json");
            Files.writeString(creds, json);

            Optional<String> token = extractor.extractTokenFromPath(creds);
            assertTrue(token.isPresent(), "Expected token in container: " + container);
            assertEquals("nested-token-in-" + container, token.get());
        }
    }

    @Test
    void extractToken_fromNestedContainerWithNonObjectValue() throws IOException {
        String json = """
                {
                  "claudeAiOauth": "not_an_object",
                  "apiKey": "top_level_key"
                }
                """;
        Path creds = tempDir.resolve("non_obj_container.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractTokenFromPath(creds);
        assertTrue(token.isPresent());
        assertEquals("top_level_key", token.get());
    }

    @Test
    void extractToken_nestedContainerWithBlankOrNullValueIgnored() throws IOException {
        String json = """
                {
                  "oauthAccount": {
                    "accessToken": "null",
                    "bearerToken": "   "
                  }
                }
                """;
        Path creds = tempDir.resolve("nested_null.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractTokenFromPath(creds);
        assertFalse(token.isPresent());
    }

    @Test
    void extractToken_stringNullAndBlankValuesIgnoredAtTopLevel() throws IOException {
        String json = """
                {
                  "accessToken": "null",
                  "apiKey": "   "
                }
                """;
        Path creds = tempDir.resolve("null_strings.json");
        Files.writeString(creds, json);

        Optional<String> token = extractor.extractTokenFromPath(creds);
        assertFalse(token.isPresent());
    }

    @Test
    void extractToken_nonExistentPath_returnsEmpty() {
        Optional<String> token = extractor.extractTokenFromPath(tempDir.resolve("missing.json"));
        assertTrue(token.isEmpty());
    }

    @Test
    void extractToken_nullPath_returnsEmpty() {
        Optional<String> token = extractor.extractTokenFromPath(null);
        assertTrue(token.isEmpty());
    }

    @Test
    void extractToken_invalidJson_returnsEmpty() throws IOException {
        Path badJson = tempDir.resolve("bad.json");
        Files.writeString(badJson, "{ invalid-json ");
        Optional<String> token = extractor.extractTokenFromPath(badJson);
        assertTrue(token.isEmpty());
    }

    @Test
    void extractToken_emptyObjectReturnsEmpty() throws IOException {
        Path emptyJson = tempDir.resolve("empty.json");
        Files.writeString(emptyJson, "{}");
        Optional<String> token = extractor.extractTokenFromPath(emptyJson);
        assertTrue(token.isEmpty());
    }

    @Test
    void extractToken_nonObjectJsonRootsReturnsEmpty() throws IOException {
        Path arrPath = tempDir.resolve("arr.json");
        Files.writeString(arrPath, "[\"accessToken\", \"sk-ant-test\"]");
        assertTrue(extractor.extractTokenFromPath(arrPath).isEmpty());

        Path nullPath = tempDir.resolve("null.json");
        Files.writeString(nullPath, "null");
        assertTrue(extractor.extractTokenFromPath(nullPath).isEmpty());

        Path numPath = tempDir.resolve("num.json");
        Files.writeString(numPath, "98765");
        assertTrue(extractor.extractTokenFromPath(numPath).isEmpty());
    }

    @Test
    void extractToken_fallbackToSystemProperties() {
        String orig1 = System.getProperty("AMSHA_CLAUDE_CODE_API_KEY");
        String orig2 = System.getProperty("amsha.llm.claude-code.api-key");
        String origOs = System.getProperty("os.name");
        String origHome = System.getProperty("user.home");
        try {
            System.setProperty("os.name", "Linux");
            System.setProperty("user.home", tempDir.resolve("empty_home").toString());

            // Test AMSHA_CLAUDE_CODE_API_KEY
            System.setProperty("AMSHA_CLAUDE_CODE_API_KEY", "claude-sys-prop-key-1");
            System.clearProperty("amsha.llm.claude-code.api-key");
            Optional<String> t1 = extractor.extractToken();
            assertTrue(t1.isPresent());
            assertEquals("claude-sys-prop-key-1", t1.get());

            // Test amsha.llm.claude-code.api-key
            System.clearProperty("AMSHA_CLAUDE_CODE_API_KEY");
            System.setProperty("amsha.llm.claude-code.api-key", "claude-sys-prop-key-2");
            Optional<String> t2 = extractor.extractToken();
            assertTrue(t2.isPresent());
            assertEquals("claude-sys-prop-key-2", t2.get());
        } finally {
            if (orig1 != null) {
                System.setProperty("AMSHA_CLAUDE_CODE_API_KEY", orig1);
            } else {
                System.clearProperty("AMSHA_CLAUDE_CODE_API_KEY");
            }
            if (orig2 != null) {
                System.setProperty("amsha.llm.claude-code.api-key", orig2);
            } else {
                System.clearProperty("amsha.llm.claude-code.api-key");
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
    void extractToken_allCandidateFilesystemPaths() throws IOException {
        String origOs = System.getProperty("os.name");
        String origHome = System.getProperty("user.home");
        String orig1 = System.getProperty("AMSHA_CLAUDE_CODE_API_KEY");
        String orig2 = System.getProperty("amsha.llm.claude-code.api-key");
        try {
            System.setProperty("os.name", "Linux");
            System.clearProperty("AMSHA_CLAUDE_CODE_API_KEY");
            System.clearProperty("amsha.llm.claude-code.api-key");

            String[][] relPaths = {
                {".claude", ".credentials.json"},
                {".claude", "credentials.json"},
                {".claude.json"}
            };

            for (int i = 0; i < relPaths.length; i++) {
                Path mockHome = tempDir.resolve("mock_claude_home_" + i);
                Path target = mockHome;
                for (String part : relPaths[i]) {
                    target = target.resolve(part);
                }
                Files.createDirectories(target.getParent());
                Files.writeString(target, "{\"accessToken\": \"claude-cand-token-" + i + "\"}");
                System.setProperty("user.home", mockHome.toString());

                Optional<String> token = extractor.extractToken();
                assertTrue(token.isPresent(), "Expected token for candidate path index " + i);
                assertEquals("claude-cand-token-" + i, token.get());
            }
        } finally {
            if (orig1 != null) {
                System.setProperty("AMSHA_CLAUDE_CODE_API_KEY", orig1);
            }
            if (orig2 != null) {
                System.setProperty("amsha.llm.claude-code.api-key", orig2);
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
        String orig1 = System.getProperty("AMSHA_CLAUDE_CODE_API_KEY");
        String orig2 = System.getProperty("amsha.llm.claude-code.api-key");
        try {
            System.setProperty("os.name", "Mac OS X");
            System.setProperty(
                    "user.home", tempDir.resolve("empty_home_keychain").toString());
            System.clearProperty("AMSHA_CLAUDE_CODE_API_KEY");
            System.clearProperty("amsha.llm.claude-code.api-key");

            extractor.extractToken();
        } finally {
            if (orig1 != null) {
                System.setProperty("AMSHA_CLAUDE_CODE_API_KEY", orig1);
            }
            if (orig2 != null) {
                System.setProperty("amsha.llm.claude-code.api-key", orig2);
            }
            if (origOs != null) {
                System.setProperty("os.name", origOs);
            }
            if (origHome != null) {
                System.setProperty("user.home", origHome);
            }
        }
    }
}
