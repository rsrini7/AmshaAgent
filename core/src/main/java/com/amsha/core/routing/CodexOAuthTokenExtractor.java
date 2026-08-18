package com.amsha.core.routing;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.amsha.common.util.LogUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

/**
 * Component - CodexOAuthTokenExtractor.
 * Reads the OAuth access token stored by Codex CLI / ChatGPT Plus login from ~/.codex/auth.json.
 */
@ApplicationScoped
public class CodexOAuthTokenExtractor {

    private static final Logger log = Logger.getLogger(CodexOAuthTokenExtractor.class);

    private static final Path AUTH_JSON_PATH = Path.of(System.getProperty("user.home"), ".codex", "auth.json");

    @Inject
    ObjectMapper objectMapper;

    /**
     * Extracts the OAuth access token from ~/.codex/auth.json.
     * Checks 'tokens.access_token' (verifying 'tokens.account_id' if present),
     * falling back to top-level 'OPENAI_API_KEY'.
     * Returns Optional.empty() if missing or unparseable.
     * MUST NOT log the raw token value.
     */
    public Optional<String> extractToken() {
        return extractTokenFromPath(AUTH_JSON_PATH);
    }

    /**
     * Extracts token from a specific path (for unit tests).
     */
    protected Optional<String> extractTokenFromPath(Path path) {
        if (path == null || !Files.exists(path)) {
            log.debugf("Codex credentials not found at %s", path);
            return Optional.empty();
        }

        try {
            byte[] content = Files.readAllBytes(path);
            ObjectMapper mapper = objectMapper != null ? objectMapper : new ObjectMapper();
            JsonNode root = mapper.readTree(content);

            // 1. Primary: tokens.access_token (ChatGPT Plus OAuth)
            JsonNode tokenNode = root.path("tokens").path("access_token");
            if (!tokenNode.isMissingNode() && !tokenNode.asText().isBlank()) {
                String accountId = root.path("tokens").path("account_id").asText(null);
                if (accountId != null && !accountId.isBlank()) {
                    log.infof("Codex credentials found (chatgpt oauth access token, account=%s).", accountId);
                } else {
                    log.info("Codex credentials found (chatgpt oauth access token).");
                }
                return Optional.of(tokenNode.asText().trim());
            }

            // 2. Fallback: OPENAI_API_KEY in auth.json
            JsonNode apiKeyNode = root.path("OPENAI_API_KEY");
            if (!apiKeyNode.isMissingNode()
                    && !apiKeyNode.asText().isBlank()
                    && !"null".equalsIgnoreCase(apiKeyNode.asText())) {
                log.info("Codex credentials found (OPENAI_API_KEY in auth.json).");
                return Optional.of(apiKeyNode.asText().trim());
            }

            log.info("Codex credentials file found, but no access token or API key present.");
            return Optional.empty();

        } catch (Exception e) {
            log.warnf("Failed to parse Codex credentials: %s", LogUtil.rootMessage(e));
            return Optional.empty();
        }
    }

    /**
     * Extracts the account_id from ~/.codex/auth.json if present.
     */
    public Optional<String> extractAccountId() {
        return extractAccountIdFromPath(AUTH_JSON_PATH);
    }

    /**
     * Extracts the account_id from a specific path (for unit tests).
     */
    protected Optional<String> extractAccountIdFromPath(Path path) {
        if (path == null || !Files.exists(path)) {
            return Optional.empty();
        }

        try {
            byte[] content = Files.readAllBytes(path);
            ObjectMapper mapper = objectMapper != null ? objectMapper : new ObjectMapper();
            JsonNode root = mapper.readTree(content);
            JsonNode accNode = root.path("tokens").path("account_id");
            if (!accNode.isMissingNode() && !accNode.asText().isBlank()) {
                return Optional.of(accNode.asText().trim());
            }
            return Optional.empty();
        } catch (Exception e) {
            log.debugf("Failed to extract Codex account_id: %s", LogUtil.rootMessage(e));
            return Optional.empty();
        }
    }
}
