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
import com.amsha.llm.api.ModelDefaults;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;

/**
 * Component - AgyOAuthTokenExtractor.
 * Reads OAuth session tokens or API keys stored by Antigravity CLI (agy) from the macOS Keychain,
 * ~/.gemini/antigravity-cli/settings.json, ~/.gemini/credentials.json, ~/.config/gcloud/application_default_credentials.json,
 * or environment variables.
 */
@ApplicationScoped
@Unremovable
public class AgyOAuthTokenExtractor {

    private static final Logger log = Logger.getLogger(AgyOAuthTokenExtractor.class);

    private static final List<String> KEYCHAIN_SERVICES =
            List.of("Antigravity", "antigravity-cli", "Gemini Code Assist", "google-antigravity");

    private static final List<String> TOKEN_CONTAINER_NAMES = List.of(
            "oauthAccount", "oauth", "credentials", "tokens", "auth", ModelDefaults.PROVIDER_GEMINI, "antigravity");

    private static final List<String> TOKEN_FIELD_NAMES = List.of(
            "apiKey", "api_key", "accessToken", "access_token", "bearerToken", "token", "oauthToken", "sessionKey");

    @Inject
    Instance<ObjectMapper> objectMapper;

    /**
     * Extracts the OAuth token from Antigravity CLI storage.
     * Order of precedence:
     * 1. macOS Keychain
     * 2. Filesystem credentials (~/.gemini/antigravity-cli/settings.json, credentials.json, oauth_creds.json)
     * 3. Environment variables (AMSHA_AGY_API_KEY, AGY_API_KEY, AMSHA_GEMINI_API_KEY, GEMINI_API_KEY, GOOGLE_API_KEY)
     *
     * <p>MUST NOT log the raw token value.
     */
    public Optional<String> extractToken() {
        // 1. Check macOS Keychain
        Optional<String> keychainToken = extractTokenFromMacKeychain();
        if (keychainToken.isPresent()) {
            log.info("Antigravity credentials found in macOS Keychain.");
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
                log.infof("Antigravity credentials found at %s", path);
                return token;
            }
            log.debugf("Antigravity credentials file found at %s, but token field missing.", path);
            return Optional.empty();
        } catch (Exception e) {
            log.warnf("Failed to parse Antigravity credentials at %s: %s", path, LogUtil.rootMessage(e));
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
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
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
                if (raw == null || raw.isBlank()) {
                    return Optional.empty();
                }
                String trimmed = raw.trim();

                // Keychain might store raw token or a JSON string
                if (trimmed.startsWith("{")) {
                    ObjectMapper mapper = objectMapper != null && objectMapper.isResolvable()
                            ? objectMapper.get()
                            : new ObjectMapper();
                    JsonNode root = mapper.readTree(trimmed);
                    return extractTokenFromJson(root);
                }
                return Optional.of(trimmed);
            }
        } catch (Exception e) {
            log.debugf("Keychain lookup for %s failed: %s", serviceName, LogUtil.rootMessage(e));
            return Optional.empty();
        }
    }

    protected Optional<String> extractTokenFromJson(JsonNode root) {
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }

        if (root.hasNonNull("expiry_date")) {
            long expiry = root.get("expiry_date").asLong();
            long now = System.currentTimeMillis();
            if (expiry > 0 && (expiry < now || (expiry < now / 1000 && expiry * 1000 < now))) {
                return Optional.empty();
            }
        }

        // Direct top-level fields
        for (String fieldName : TOKEN_FIELD_NAMES) {
            if (root.hasNonNull(fieldName)) {
                String val = root.get(fieldName).asText();
                if (val != null && !val.isBlank()) {
                    return Optional.of(val.trim());
                }
            }
        }

        // Check nested containers
        for (String containerName : TOKEN_CONTAINER_NAMES) {
            if (root.hasNonNull(containerName)) {
                JsonNode container = root.get(containerName);
                if (container.isObject()) {
                    for (String fieldName : TOKEN_FIELD_NAMES) {
                        if (container.hasNonNull(fieldName)) {
                            String val = container.get(fieldName).asText();
                            if (val != null && !val.isBlank()) {
                                return Optional.of(val.trim());
                            }
                        }
                    }
                }
            }
        }

        return Optional.empty();
    }

    private List<Path> getCandidateFilePaths() {
        String userHome = System.getProperty("user.home", "");
        if (userHome.isBlank()) {
            return List.of();
        }

        List<Path> paths = new ArrayList<>();
        paths.add(Path.of(userHome, ".gemini", "antigravity-cli", "settings.json"));
        paths.add(Path.of(userHome, ".gemini", "antigravity-cli", "credentials.json"));
        paths.add(Path.of(userHome, ".gemini", "credentials.json"));
        paths.add(Path.of(userHome, ".gemini", "oauth_creds.json"));
        paths.add(Path.of(userHome, ".gemini", "settings.json"));
        return paths;
    }

    private Optional<String> extractTokenFromEnvironment() {
        Optional<String> prop = Optional.ofNullable(System.getProperty(LlmConfigKeys.AGY_API_KEY))
                .filter(val -> !val.isBlank())
                .map(String::trim);
        if (prop.isPresent()) {
            return prop;
        }

        List<String> envVars = List.of(
                ConfigKeys.AGY_API_KEY,
                "AGY_API_KEY",
                "ANTIGRAVITY_API_KEY",
                ConfigKeys.GEMINI_API_KEY,
                "GEMINI_API_KEY",
                "GOOGLE_API_KEY");

        for (String var : envVars) {
            String val = System.getenv(var);
            if (val != null && !val.isBlank()) {
                log.infof("Antigravity credentials loaded from environment variable %s.", var);
                return Optional.of(val.trim());
            }
        }
        return Optional.empty();
    }
}
