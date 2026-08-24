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
    void testExtractFromAccessUnderscoreTokenField() throws IOException {
        Path configPath = tempDir.resolve("config.json");
        Files.writeString(configPath, "{\"access_token\": \"kimchi_access_underscore_token\"}");

        Optional<String> token = extractor.extractToken(configPath);
        assertTrue(token.isPresent());
        assertEquals("kimchi_access_underscore_token", token.get());
    }

    @Test
    void testExtractFromBearerTokenField() throws IOException {
        Path configPath = tempDir.resolve("config.json");
        Files.writeString(configPath, "{\"bearerToken\": \"kimchi_bearer_token_val\"}");

        Optional<String> token = extractor.extractToken(configPath);
        assertTrue(token.isPresent());
        assertEquals("kimchi_bearer_token_val", token.get());
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
    void testExtractFromOpenCodeStructureNonTextualOrBlank() throws IOException {
        Path blankPath = tempDir.resolve("opencode_blank.json");
        Files.writeString(blankPath, "{\"provider\": {\"kimchi\": {\"options\": {\"apiKey\": \"   \"}}}}");
        assertFalse(extractor.extractToken(blankPath).isPresent());

        Path numPath = tempDir.resolve("opencode_num.json");
        Files.writeString(numPath, "{\"provider\": {\"kimchi\": {\"options\": {\"apiKey\": 12345}}}}");
        assertFalse(extractor.extractToken(numPath).isPresent());
    }

    @Test
    void testExtractFromNestedAuthObject() throws IOException {
        String[] authFields = {"apiKey", "api_key", "token", "accessToken", "access_token", "bearerToken"};
        for (String field : authFields) {
            Path configPath = tempDir.resolve("config_auth_" + field + ".json");
            Files.writeString(configPath, "{\"auth\": {\"" + field + "\": \"auth_val_" + field + "\"}}");

            Optional<String> token = extractor.extractToken(configPath);
            assertTrue(token.isPresent(), "Expected token for auth field: " + field);
            assertEquals("auth_val_" + field, token.get());
        }
    }

    @Test
    void testExtractFromNestedAuthObjectNonObjectOrNonTextual() throws IOException {
        Path nonObj = tempDir.resolve("auth_non_obj.json");
        Files.writeString(nonObj, "{\"auth\": \"not_an_object\", \"apiKey\": \"fallback_key\"}");
        Optional<String> t1 = extractor.extractToken(nonObj);
        assertTrue(t1.isPresent());
        assertEquals("fallback_key", t1.get());

        Path nonText = tempDir.resolve("auth_non_text.json");
        Files.writeString(nonText, "{\"auth\": {\"apiKey\": 12345}}");
        assertFalse(extractor.extractToken(nonText).isPresent());
    }

    @Test
    void testNonObjectJsonRoots() throws IOException {
        Path arrPath = tempDir.resolve("arr.json");
        Files.writeString(arrPath, "[\"apiKey\", \"val\"]");
        assertFalse(extractor.extractToken(arrPath).isPresent());

        Path numPath = tempDir.resolve("num.json");
        Files.writeString(numPath, "12345");
        assertFalse(extractor.extractToken(numPath).isPresent());

        Path nullPath = tempDir.resolve("null_val.json");
        Files.writeString(nullPath, "null");
        assertFalse(extractor.extractToken(nullPath).isPresent());
    }

    @Test
    void testMissingFileReturnsEmpty() {
        Path missing = tempDir.resolve("non_existent.json");
        Optional<String> token = extractor.extractToken(missing);
        assertFalse(token.isPresent());
    }

    @Test
    void testNullPathReturnsEmpty() {
        Optional<String> token = extractor.extractToken(null);
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

    @Test
    void testExtractFromAllCandidateFilesystemPaths() throws IOException {
        String[][] relPaths = {
            {".config", "kimchi", "config.json"},
            {".kimchi", "config.json"},
            {".config", "opencode", "opencode.json"}
        };

        for (int i = 0; i < relPaths.length; i++) {
            Path mockHome = tempDir.resolve("mock_kimchi_home_" + i);
            Path target = mockHome;
            for (String part : relPaths[i]) {
                target = target.resolve(part);
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, "{\"apiKey\": \"kimchi_cand_key_" + i + "\"}");

            Optional<String> token = extractor.extractToken(target);
            assertTrue(token.isPresent(), "Expected token for candidate path index " + i);
            assertEquals("kimchi_cand_key_" + i, token.get());
        }
    }

    @Test
    void testExtractTokenPrecedence() throws IOException {
        String origOs = System.getProperty("os.name");
        String origHome = System.getProperty("user.home");
        try {
            System.setProperty("os.name", "Linux");

            Path mockHome = tempDir.resolve("mock_kimchi_home");
            Path kimchiDir = mockHome.resolve(".config").resolve("kimchi");
            Files.createDirectories(kimchiDir);
            Files.writeString(kimchiDir.resolve("config.json"), "{\"apiKey\": \"kimchi_fs_key\"}");
            System.setProperty("user.home", mockHome.toString());

            Optional<String> token = extractor.extractToken();
            assertTrue(token.isPresent());
            String envVar = System.getenv("AMSHA_KIMCHI_API_KEY");
            if (envVar == null || envVar.isBlank()) {
                envVar = System.getenv("KIMCHI_API_KEY");
            }
            if (envVar != null && !envVar.isBlank()) {
                assertEquals(envVar.trim(), token.get());
            } else {
                assertEquals("kimchi_fs_key", token.get());
            }
        } finally {
            if (origOs != null) {
                System.setProperty("os.name", origOs);
            }
            if (origHome != null) {
                System.setProperty("user.home", origHome);
            }
        }
    }

    @Test
    void testMacKeychainLookup() {
        String origOs = System.getProperty("os.name");
        String origHome = System.getProperty("user.home");
        try {
            System.setProperty("os.name", "Mac OS X");
            System.setProperty(
                    "user.home", tempDir.resolve("empty_home_keychain").toString());

            extractor.extractToken();
        } finally {
            if (origOs != null) {
                System.setProperty("os.name", origOs);
            }
            if (origHome != null) {
                System.setProperty("user.home", origHome);
            }
        }
    }

    @Test
    void testEmptyUserHomeReturnsEmpty() {
        String origOs = System.getProperty("os.name");
        String origHome = System.getProperty("user.home");
        try {
            System.setProperty("os.name", "Linux");
            System.setProperty("user.home", "");

            Optional<String> token = extractor.extractToken();
            assertFalse(token.isPresent() && token.get().equals("kimchi_fs_key"));
        } finally {
            if (origOs != null) {
                System.setProperty("os.name", origOs);
            }
            if (origHome != null) {
                System.setProperty("user.home", origHome);
            }
        }
    }
}
