package com.arcclaw.app.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.arcclaw.common.util.LogUtil;

import org.jboss.logging.Logger;

/**
 * Handles .env file loading and validation of core environment variables.
 */
public class EnvConfigInitializer {

    private static final Logger log = Logger.getLogger(EnvConfigInitializer.class);

    private static final Pattern ENV_PATTERN = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_.]*?)\\s*=\\s*(.*?)\\s*$");

    private static final Set<String> BASE64_KEYS = Set.of(
            "ARCCLAW_VAULT_PEPPER",
            "ARCCLAW_AUDIT_HMAC_KEY",
            "ARCCLAW_API_KEYS_INTERNAL",
            "ARCCLAW_API_KEYS_CI",
            "ARCCLAW_API_KEYS_ADMIN");

    public static void loadDotEnv(Path dir) {
        Path envFile = dir.resolve(".env");
        if (!Files.exists(envFile)) return;

        try (BufferedReader reader = Files.newBufferedReader(envFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                Matcher m = ENV_PATTERN.matcher(line);
                if (m.find()) {
                    String key = m.group(1);
                    String value = m.group(2).trim();

                    if (value.contains(" #") || value.contains("\t#")) {
                        if (!(value.startsWith("\"") || value.startsWith("'"))) {
                            int idx = value.indexOf(" #");
                            if (idx == -1) idx = value.indexOf("\t#");
                            value = value.substring(0, idx).trim();
                        }
                    }

                    // Last wins — later values override earlier ones (handles duplicate keys)
                    if (System.getenv(key) == null) {
                        System.setProperty(key, value);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[ArcClaw] ⚠️  Warning: Failed to read .env: " + e.getMessage());
        }
    }

    public static void validateAndAutoFixSecurityKeys(Path binaryDir) {
        Path envFile = binaryDir.resolve(".env");
        boolean anyFixed = false;
        for (String key : BASE64_KEYS) {
            String value = System.getProperty(key, System.getenv(key));
            if (value == null || value.isBlank()) continue;
            boolean valid = false;
            try {
                byte[] decoded = Base64.getDecoder().decode(value);
                valid = decoded.length >= 16;
            } catch (IllegalArgumentException e) {
                log.warnf("Invalid Base64 for key %s, will regenerate: %s", key, LogUtil.rootMessage(e));
            }
            if (!valid) {
                try {
                    String generated = Base64.getEncoder()
                            .encodeToString(SecureRandom.getInstanceStrong().generateSeed(32));
                    System.err.println("[ArcClaw] ⚠️  " + key + " invalid Base64 - regenerating.");
                    patchEnvFile(envFile, key, generated, PatchMode.UPDATE);
                    System.setProperty(key, generated);
                    anyFixed = true;
                } catch (Exception e) {
                    System.err.println(
                            "[ArcClaw] ❌ Failed to generate security key for " + key + ": " + e.getMessage());
                }
            }
        }
        if (anyFixed) loadDotEnv(binaryDir);
    }

    public static void validateAndAutoFixEmbeddingConfig(Path binaryDir) {
        Path envFile = binaryDir.resolve(".env");
        String provider = System.getProperty("ARCCLAW_EMBED_PROVIDER", System.getenv("ARCCLAW_EMBED_PROVIDER"));
        String baseUrl = System.getProperty("ARCCLAW_EMBED_BASE_URL", System.getenv("ARCCLAW_EMBED_BASE_URL"));

        if (baseUrl != null && baseUrl.contains("jina.ai") && !"jina".equalsIgnoreCase(provider)) {
            System.err.println("[ArcClaw] ℹ️  Embedding provider mismatch - auto-fixing to 'jina'.");
            patchEnvFile(envFile, "ARCCLAW_EMBED_PROVIDER", "jina", PatchMode.FORCE);
            System.setProperty("ARCCLAW_EMBED_PROVIDER", "jina");
        }

        if ("ollama".equalsIgnoreCase(provider) && (baseUrl == null || baseUrl.contains("jina.ai"))) {
            String defaultOllamaUrl = "http://localhost:11434";
            System.err.println("[ArcClaw] ℹ️  Ollama embedding URL missing - auto-setting to: " + defaultOllamaUrl);
            patchEnvFile(envFile, "ARCCLAW_EMBED_BASE_URL", defaultOllamaUrl, PatchMode.UPDATE);
            System.setProperty("ARCCLAW_EMBED_BASE_URL", defaultOllamaUrl);
        }
    }

    public enum PatchMode {
        INIT,
        UPDATE,
        FORCE
    }

    public static void patchEnvFile(Path envFile, String key, String value, PatchMode mode) {
        try {
            if (!Files.exists(envFile)) {
                Files.writeString(envFile, key + "=" + value + System.lineSeparator());
                return;
            }
            String content = Files.readString(envFile);
            Pattern pattern = Pattern.compile("(?m)^" + Pattern.quote(key) + "=.*$");
            Matcher matcher = pattern.matcher(content);

            if (matcher.find()) {
                String currentLine = matcher.group();
                String newLine = key + "=" + value;

                boolean needsPatch =
                        switch (mode) {
                            case FORCE -> true;
                            case UPDATE -> !currentLine.equals(newLine);
                            case INIT -> {
                                String currentValue =
                                        currentLine.substring(key.length() + 1).trim();
                                yield currentValue.isEmpty()
                                        || currentValue.contains("xxxx")
                                        || currentValue.contains("<")
                                        || currentValue.contains("changeme")
                                        || currentValue.contains("ABCdef");
                            }
                        };

                if (needsPatch) {
                    content = pattern.matcher(content).replaceAll(Matcher.quoteReplacement(newLine));
                    Files.writeString(envFile, content);
                }
            } else {
                String separator = content.endsWith(System.lineSeparator()) ? "" : System.lineSeparator();
                Files.writeString(
                        envFile, separator + key + "=" + value + System.lineSeparator(), StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            log.warnf("[ArcClaw] Could not update .env: %s", LogUtil.rootMessage(e));
        }
    }

    public static void extractEnvExampleIfNeeded(Path dir) {
        Path envFile = dir.resolve(".env");
        if (Files.exists(envFile)) return;

        Path exampleFile = dir.resolve(".env.example");
        if (!Files.exists(exampleFile)) {
            try (InputStream in = EnvConfigInitializer.class.getResourceAsStream("/env-example")) {
                if (in == null) return;
                Files.copy(in, exampleFile, StandardCopyOption.REPLACE_EXISTING);
                System.err.println("[ArcClaw] No .env found. Created .env.example next to the binary.");
            } catch (IOException e) {
                log.warnf("Failed to extract .env.example: %s", LogUtil.rootMessage(e));
            }
        }
    }
}
