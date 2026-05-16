package com.arcclaw.core.routing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

/**
 * Component A3 - ClaudeCodeOAuthTokenExtractor.
 * Reads the OAuth token stored by Claude Code CLI from ~/.claude/credentials.json.
 */
@ApplicationScoped
public class ClaudeCodeOAuthTokenExtractor {

    private static final Logger log = Logger.getLogger(ClaudeCodeOAuthTokenExtractor.class);

    private static final Path CREDENTIALS_PATH =
        Path.of(System.getProperty("user.home"), ".claude", "credentials.json");

    @Inject
    ObjectMapper objectMapper;

    /**
     * Extracts the OAuth token from the Claude Code credentials file.
     * Returns Optional.empty() if missing or unparseable.
     * MUST NOT log the raw token value.
     */
    public Optional<String> extractToken() {
        if (!Files.exists(CREDENTIALS_PATH)) {
            log.infof("Claude Code credentials not found at %s", CREDENTIALS_PATH);
            return Optional.empty();
        }

        try {
            byte[] content = Files.readAllBytes(CREDENTIALS_PATH);
            JsonNode root = objectMapper.readTree(content);
            
            // Extract 'accessToken' (primary) or 'bearerToken' (fallback)
            JsonNode tokenNode = root.path("accessToken");
            if (tokenNode.isMissingNode() || tokenNode.asText().isBlank()) {
                tokenNode = root.path("bearerToken");
            }

            if (tokenNode.isMissingNode() || tokenNode.asText().isBlank()) {
                log.info("Claude Code credentials found, but token field missing.");
                return Optional.empty();
            }

            log.info("Claude Code credentials found.");
            return Optional.of(tokenNode.asText());

        } catch (Exception e) {
            log.warnf("Failed to parse Claude Code credentials: %s", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * For unit tests only.
     */
    protected Optional<String> extractToken(Path customPath) {
        if (!Files.exists(customPath)) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readAllBytes(customPath));
            JsonNode tokenNode = root.path("accessToken");
            if (tokenNode.isMissingNode()) tokenNode = root.path("bearerToken");
            return Optional.ofNullable(tokenNode.asText(null));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
