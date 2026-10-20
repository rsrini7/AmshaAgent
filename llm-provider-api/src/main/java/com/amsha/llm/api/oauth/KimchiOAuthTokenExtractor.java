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
import com.amsha.llm.api.ModelDefaults;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;

/**
 * Component - KimchiOAuthTokenExtractor.
 * Reads API keys stored by Kimchi CLI from ~/.config/kimchi/config.json,
 * ~/.config/opencode/opencode.json, macOS Keychain, or environment variables.
 */
@ApplicationScoped
@Unremovable
public class KimchiOAuthTokenExtractor {

    private static final Logger log = Logger.getLogger(KimchiOAuthTokenExtractor.class);

    private static final List<String> KEYCHAIN_SERVICES =
            List.of(ModelDefaults.PROVIDER_KIMCHI, "kimchi-dev", "kimchicorp");

    private static final List<String> TOKEN_FIELD_NAMES =
            List.of("apiKey", "api_key", "token", "accessToken", "access_token", "bearerToken");

    @Inject
    Instance<ObjectMapper> objectMapper;

    /**
     * Extracts the API key / token from Kimchi CLI storage.
     * Order of precedence:
     * 1. Environment variables (AMSHA_KIMCHI_API_KEY, KIMCHI_API_KEY)
     * 2. Filesystem credentials (~/.config/kimchi/config.json, ~/.config/opencode/opencode.json)
     * 3. macOS Keychain
     *
     * <p>MUST NOT log the raw token value.
     */
    public Optional<String> extractToken() {
        // 1. Check environment variables
        Optional<String> envToken = extractTokenFromEnvironment();
        if (envToken.isPresent()) {
            return envToken;
        }

        // 2. Check filesystem candidate paths
        for (Path path : getCandidateFilePaths()) {
            Optional<String> fileToken = extractTokenFromPath(path);
            if (fileToken.isPresent()) {
                return fileToken;
            }
        }

        // 3. Check macOS Keychain
        Optional<String> keychainToken = extractTokenFromMacKeychain();
        if (keychainToken.isPresent()) {
            log.info("Kimchi credentials found in macOS Keychain.");
            return keychainToken;
        }

        return Optional.empty();
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
                log.infof("Kimchi credentials found at %s", path);
                return token;
            }
            log.debugf("Kimchi credentials file found at %s, but apiKey field missing.", path);
            return Optional.empty();
        } catch (Exception e) {
            log.warnf("Failed to parse Kimchi credentials at %s: %s", path, LogUtil.rootMessage(e));
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

            if (process.exitValue() == 0) {
                try (BufferedReader reader =
                        new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String password = reader.readLine();
                    if (password != null && !password.isBlank()) {
                        return Optional.of(password.trim());
                    }
                }
            }
        } catch (Exception _) {
            // Keychain lookup failed or service not found
        }
        return Optional.empty();
    }

    private List<Path> getCandidateFilePaths() {
        String userHome = System.getProperty("user.home", "");
        if (userHome.isBlank()) {
            return List.of();
        }

        List<Path> paths = new ArrayList<>();
        paths.add(Path.of(userHome, ".config", ModelDefaults.PROVIDER_KIMCHI, "config.json"));
        paths.add(Path.of(userHome, ".kimchi", "config.json"));
        paths.add(Path.of(userHome, ".config", ModelDefaults.PROVIDER_OPENCODE, "opencode.json"));

        return paths;
    }

    private Optional<String> extractTokenFromEnvironment() {
        List<String> envVars = List.of(ConfigKeys.KIMCHI_API_KEY, "KIMCHI_API_KEY");

        for (String varName : envVars) {
            String val = System.getenv(varName);
            if (val != null && !val.isBlank()) {
                log.infof("Kimchi credentials found in environment variable %s", varName);
                return Optional.of(val.trim());
            }
        }
        return Optional.empty();
    }

    private Optional<String> extractTokenFromJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return Optional.empty();
        }

        // Direct top-level fields (e.g. config.json)
        for (String fieldName : TOKEN_FIELD_NAMES) {
            JsonNode field = node.path(fieldName);
            if (field.isTextual() && !field.asText().isBlank()) {
                return Optional.of(field.asText().trim());
            }
        }

        // OpenCode provider schema (provider.kimchi.options.apiKey)
        JsonNode opencodeKey = node.path("provider")
                .path(ModelDefaults.PROVIDER_KIMCHI)
                .path("options")
                .path("apiKey");
        if (opencodeKey.isTextual() && !opencodeKey.asText().isBlank()) {
            return Optional.of(opencodeKey.asText().trim());
        }

        // Nested auth container objects
        JsonNode authNode = node.path("auth");
        if (authNode.isObject()) {
            for (String fieldName : TOKEN_FIELD_NAMES) {
                JsonNode field = authNode.path(fieldName);
                if (field.isTextual() && !field.asText().isBlank()) {
                    return Optional.of(field.asText().trim());
                }
            }
        }

        return Optional.empty();
    }
}
