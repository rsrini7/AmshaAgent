package com.amsha.llm.api.oauth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.amsha.common.config.ConfigKeys;
import com.amsha.common.util.LogUtil;
import com.amsha.llm.api.LlmConfigKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;

/**
 * Component - ClaudeCodeOAuthTokenExtractor.
 * Reads OAuth session tokens or API keys stored by Claude Code CLI from the macOS Keychain,
 * ~/.claude/.credentials.json, ~/.claude/credentials.json, ~/.claude.json, or environment variables.
 */
@ApplicationScoped
@Unremovable
public class ClaudeCodeOAuthTokenExtractor {

    private static final Logger log = Logger.getLogger(ClaudeCodeOAuthTokenExtractor.class);

    private static final List<String> KEYCHAIN_SERVICES =
            List.of("Claude Code-credentials", "claude-code-credentials", "Claude Code");

    private static final List<String> TOKEN_CONTAINER_NAMES =
            List.of("claudeAiOauth", "oauthAccount", "oauth", "tokens", "claude");

    private static final List<String> TOKEN_FIELD_NAMES = List.of(
            "accessToken", "access_token", "bearerToken", "token", "apiKey", "api_key", "oauthToken", "sessionKey");

    @Inject
    Instance<ObjectMapper> objectMapper;

    /**
     * Extracts the OAuth token from the Claude Code CLI storage.
     * Order of precedence:
     * 1. macOS Keychain (standard on macOS)
     * 2. Filesystem credentials (~/.claude/.credentials.json, credentials.json, .claude.json)
     * 3. Environment variables (AMSHA_CLAUDE_CODE_API_KEY, CLAUDE_CODE_OAUTH_TOKEN)
     *
     * <p>MUST NOT log the raw token value.
     */
    public Optional<String> extractToken() {
        // 1. Check macOS Keychain
        Optional<String> keychainToken = extractTokenFromMacKeychain();
        if (keychainToken.isPresent()) {
            log.info("Claude Code credentials found in macOS Keychain.");
            return keychainToken;
        }

        // 2. Check filesystem candidate paths
        for (Path path : getCandidateFilePaths()) {
            Optional<String> fileToken = extractTokenFromPath(path);
            if (fileToken.isPresent()) {
                return fileToken;
            }
        }

        // 3. Check environment variables
        return extractTokenFromEnvironment();
    }

    /**
     * Extracts token from a specific path (for unit tests or direct file lookups).
     */
    public Optional<String> extractToken(Path customPath) {
        return extractTokenFromPath(customPath);
    }

    protected Optional<String> extractTokenFromPath(Path path) {
        if (path == null || !Files.exists(path)) {
            return Optional.empty();
        }

        byte[] content = null;
        try {
            content = Files.readAllBytes(path);
            ObjectMapper mapper =
                    objectMapper != null && objectMapper.isResolvable() ? objectMapper.get() : new ObjectMapper();
            JsonNode root = mapper.readTree(content);
            Optional<String> token = extractTokenFromJson(root);
            if (token.isPresent()) {
                log.infof("Claude Code credentials found at %s", path);
                return token;
            }
            log.debugf("Claude Code credentials file found at %s, but token field missing.", path);
            return Optional.empty();
        } catch (Exception e) {
            log.warnf("Failed to parse Claude Code credentials at %s: %s", path, LogUtil.rootMessage(e));
            return Optional.empty();
        } finally {
            if (content != null) {
                Arrays.fill(content, (byte) 0);
            }
        }
    }

    private Optional<String> extractTokenFromMacKeychain() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("mac")) {
            return Optional.empty();
        }

        for (String service : KEYCHAIN_SERVICES) {
            Optional<String> token = readKeychainService(service);
            if (token.isPresent()) {
                return token;
            }
        }
        return Optional.empty();
    }

    private Optional<String> readKeychainService(String serviceName) {
        try {
            Process process = new ProcessBuilder("security", "find-generic-password", "-s", serviceName, "-w")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return Optional.empty();
            }
            if (process.exitValue() != 0) {
                return Optional.empty();
            }
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String raw = reader.readLine();
                if (raw == null || raw.isBlank()) return Optional.empty();
                return parseTokenString(raw.trim());
            }
        } catch (Exception e) {
            log.debugf("Could not read macOS Keychain for service '%s': %s", serviceName, LogUtil.rootMessage(e));
            return Optional.empty();
        }
    }

    private Optional<String> parseTokenString(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        if (raw.startsWith("{") && raw.endsWith("}")) {
            try {
                ObjectMapper mapper =
                        objectMapper != null && objectMapper.isResolvable() ? objectMapper.get() : new ObjectMapper();
                JsonNode node = mapper.readTree(raw);
                return extractTokenFromJson(node);
            } catch (Exception _) {
                // Not valid JSON, treat as raw token
            }
        }
        return Optional.of(raw);
    }

    private Optional<String> extractTokenFromJson(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return Optional.empty();
        }

        // Direct fields
        for (String fieldName : TOKEN_FIELD_NAMES) {
            JsonNode node = root.path(fieldName);
            if (!node.isMissingNode() && !node.asText().isBlank() && !"null".equalsIgnoreCase(node.asText())) {
                return Optional.of(node.asText().trim());
            }
        }

        // Nested in 'claudeAiOauth', 'oauthAccount', 'oauth', 'tokens', or 'claude'
        for (String containerName : TOKEN_CONTAINER_NAMES) {
            JsonNode container = root.path(containerName);
            if (!container.isMissingNode() && container.isObject()) {
                for (String fieldName : TOKEN_FIELD_NAMES) {
                    JsonNode nested = container.path(fieldName);
                    if (!nested.isMissingNode()
                            && !nested.asText().isBlank()
                            && !"null".equalsIgnoreCase(nested.asText())) {
                        return Optional.of(nested.asText().trim());
                    }
                }
            }
        }

        return Optional.empty();
    }

    private List<Path> getCandidateFilePaths() {
        List<Path> paths = new ArrayList<>();
        String configDir = System.getenv("CLAUDE_CONFIG_DIR");
        if (configDir != null && !configDir.isBlank()) {
            paths.add(Path.of(configDir, ".credentials.json"));
            paths.add(Path.of(configDir, "credentials.json"));
        }

        String userHome = System.getProperty("user.home", ".");
        paths.add(Path.of(userHome, ".claude", ".credentials.json"));
        paths.add(Path.of(userHome, ".claude", "credentials.json"));
        paths.add(Path.of(userHome, ".claude.json"));
        return paths;
    }

    private Optional<String> extractTokenFromEnvironment() {
        return Optional.ofNullable(System.getenv(ConfigKeys.CLAUDE_CODE_API_KEY))
                .filter(k -> !k.isBlank())
                .or(() -> Optional.ofNullable(System.getenv("CLAUDE_CODE_OAUTH_TOKEN"))
                        .filter(k -> !k.isBlank()))
                .or(() -> Optional.ofNullable(System.getProperty(ConfigKeys.CLAUDE_CODE_API_KEY))
                        .filter(k -> !k.isBlank()))
                .or(() -> Optional.ofNullable(System.getProperty(LlmConfigKeys.CLAUDE_CODE_API_KEY))
                        .filter(k -> !k.isBlank()));
    }
}
