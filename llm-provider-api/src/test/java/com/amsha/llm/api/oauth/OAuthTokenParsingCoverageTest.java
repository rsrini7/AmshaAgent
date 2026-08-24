package com.amsha.llm.api.oauth;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class OAuthTokenParsingCoverageTest {

    @Test
    void testAgyExtractorJsonVariants() throws Exception {
        AgyOAuthTokenExtractor extractor = new AgyOAuthTokenExtractor();
        Path tempDir = Files.createTempDirectory("agy-oauth-test");

        // Format 1: top level apiKey
        Path f1 = tempDir.resolve("f1.json");
        Files.writeString(f1, "{\"apiKey\": \"agy_key_123\"}");
        assertEquals(Optional.of("agy_key_123"), extractor.extractToken(f1));

        // Format 2: nested in oauthAccount
        Path f2 = tempDir.resolve("f2.json");
        Files.writeString(f2, "{\"oauthAccount\": {\"token\": \"tok_nested_456\"}}");
        assertEquals(Optional.of("tok_nested_456"), extractor.extractToken(f2));

        // Format 3: nested in credentials -> bearerToken
        Path f3 = tempDir.resolve("f3.json");
        Files.writeString(f3, "{\"credentials\": {\"bearerToken\": \"bearer_789\"}}");
        assertEquals(Optional.of("bearer_789"), extractor.extractToken(f3));

        // Format 4: non-existent / empty
        assertEquals(Optional.empty(), extractor.extractToken(tempDir.resolve("missing.json")));
    }

    @Test
    void testClaudeCodeExtractorJsonVariants() throws Exception {
        ClaudeCodeOAuthTokenExtractor extractor = new ClaudeCodeOAuthTokenExtractor();
        Path tempDir = Files.createTempDirectory("claude-oauth-test");

        Path f1 = tempDir.resolve("claude1.json");
        Files.writeString(f1, "{\"sessionKey\": \"sk-ant-session-123\"}");
        assertEquals(Optional.of("sk-ant-session-123"), extractor.extractToken(f1));

        Path f2 = tempDir.resolve("claude2.json");
        Files.writeString(f2, "{\"oauth\": {\"accessToken\": \"sk-ant-nested-456\"}}");
        assertEquals(Optional.of("sk-ant-nested-456"), extractor.extractToken(f2));
    }

    @Test
    void testKimchiExtractorJsonVariants() throws Exception {
        KimchiOAuthTokenExtractor extractor = new KimchiOAuthTokenExtractor();
        Path tempDir = Files.createTempDirectory("kimchi-oauth-test");

        Path f1 = tempDir.resolve("kimchi1.json");
        Files.writeString(f1, "{\"api_key\": \"km_key_123\"}");
        assertEquals(Optional.of("km_key_123"), extractor.extractToken(f1));

        Path f2 = tempDir.resolve("kimchi2.json");
        Files.writeString(f2, "{\"auth\": {\"token\": \"km_tok_456\"}}");
        assertEquals(Optional.of("km_tok_456"), extractor.extractToken(f2));
    }
}
