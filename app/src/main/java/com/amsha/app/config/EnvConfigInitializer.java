package com.amsha.app.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.amsha.common.util.LogUtil;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.security.api.VaultConstants;

import org.jboss.logging.Logger;

/**
 * Handles .env file loading and validation of core environment variables.
 */
public class EnvConfigInitializer {

    private static final Logger log = Logger.getLogger(EnvConfigInitializer.class);

    private static final Pattern ENV_PATTERN = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_.]*?)\\s*=\\s*(.*?)\\s*$");

    private static final Set<String> BASE64_KEYS = Set.of(
            VaultConstants.VAULT_PEPPER,
            VaultConstants.AUDIT_HMAC_KEY,
            VaultConstants.API_KEYS_INTERNAL,
            VaultConstants.API_KEYS_CI,
            VaultConstants.API_KEYS_ADMIN);
    private static final String CHANGE_LOG_SUFFIX = ".changes.log";
    private static final DateTimeFormatter BACKUP_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS").withZone(ZoneOffset.UTC);

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
            System.err.println("[Amsha Agent] ⚠️  Warning: Failed to read .env: " + e.getMessage());
        }
    }

    public static void validateSecurityKeys(Path binaryDir) {
        Path envFile = binaryDir.resolve(".env");
        for (String key : BASE64_KEYS) {
            String value = System.getProperty(key, System.getenv(key));
            if (value == null || value.isBlank()) continue;
            boolean valid = false;
            try {
                byte[] decoded = Base64.getDecoder().decode(value);
                valid = decoded.length >= 16;
            } catch (IllegalArgumentException e) {
                log.warnf("Invalid Base64 for key %s: %s", key, LogUtil.rootMessage(e));
            }
            if (!valid) {
                System.err.println();
                System.err.println("[Amsha Agent] ❌ INVALID SECURITY KEY");
                System.err.println("  Key: " + key);
                System.err.println("  Reason: Invalid Base64 value (too short or malformed)");
                System.err.println("  Fix: Generate a new key and update your .env manually:");
                System.err.println("    openssl rand -base64 32");
                System.err.println("  Then set: " + key + "=<generated value>");
                System.err.println("  File: " + envFile.toAbsolutePath());
                System.err.println();
            }
        }
    }

    public static void validateEmbeddingConfig(Path binaryDir) {
        Path envFile = binaryDir.resolve(".env");
        String provider = System.getProperty("AMSHA_EMBED_PROVIDER", System.getenv("AMSHA_EMBED_PROVIDER"));
        String baseUrl = System.getProperty("AMSHA_EMBED_BASE_URL", System.getenv("AMSHA_EMBED_BASE_URL"));

        if (baseUrl != null && baseUrl.contains("jina.ai") && !"jina".equalsIgnoreCase(provider)) {
            System.err.println();
            System.err.println("[Amsha Agent] ⚠️  EMBEDDING CONFIG MISMATCH");
            System.err.println(
                    "  Issue: AMSHA_EMBED_BASE_URL points to jina.ai but AMSHA_EMBED_PROVIDER is '" + provider + "'");
            System.err.println("  Fix: Set AMSHA_EMBED_PROVIDER=jina in your .env");
            System.err.println("  File: " + envFile.toAbsolutePath());
            System.err.println();
        }

        if (ModelDefaults.PROVIDER_OLLAMA.equalsIgnoreCase(provider)
                && (baseUrl == null || baseUrl.contains("jina.ai"))) {
            System.err.println();
            System.err.println("[Amsha Agent] ⚠️  EMBEDDING CONFIG INCOMPLETE");
            System.err.println(
                    "  Issue: Ollama embedding provider selected but AMSHA_EMBED_BASE_URL is missing or incorrect");
            System.err.println("  Fix: Set AMSHA_EMBED_BASE_URL=http://localhost:11434 in your .env");
            System.err.println("  File: " + envFile.toAbsolutePath());
            System.err.println();
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
                reportChange(envFile, key, "<unset>", valueState(value));
                return;
            }
            String content = Files.readString(envFile);
            Pattern pattern = Pattern.compile("(?m)^" + Pattern.quote(key) + "=.*$");
            Matcher matcher = pattern.matcher(content);

            if (matcher.find()) {
                String currentLine = matcher.group();
                String currentValue = currentLine.substring(key.length() + 1).trim();
                String newLine = key + "=" + value;

                boolean needsPatch =
                        switch (mode) {
                            case FORCE -> true;
                            case UPDATE -> !currentLine.equals(newLine);
                            case INIT ->
                                currentValue.isEmpty()
                                        || currentValue.contains("xxxx")
                                        || currentValue.contains("<")
                                        || currentValue.contains("changeme")
                                        || currentValue.contains("ABCdef");
                        };

                if (needsPatch) {
                    content = pattern.matcher(content).replaceAll(Matcher.quoteReplacement(newLine));
                    writeWithBackup(envFile, content);
                    reportChange(envFile, key, valueState(currentValue), valueState(value));
                }
            } else {
                String separator = content.endsWith(System.lineSeparator()) ? "" : System.lineSeparator();
                writeWithBackup(envFile, content + separator + key + "=" + value + System.lineSeparator());
                reportChange(envFile, key, "<unset>", valueState(value));
            }
        } catch (IOException e) {
            log.warnf("[Amsha Agent] Could not update .env: %s", LogUtil.rootMessage(e));
        }
    }

    private static void writeWithBackup(Path envFile, String content) throws IOException {
        Path target = envFile.toAbsolutePath().normalize();
        createTimestampedBackup(target);
        Path temporary = Files.createTempFile(target.getParent(), ".env-", ".tmp");
        try {
            Files.copy(target, temporary, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(temporary, content);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path createTimestampedBackup(Path envFile) throws IOException {
        String timestamp = BACKUP_TIMESTAMP_FORMATTER.format(Instant.now());
        Path backup;
        int suffix = 0;
        do {
            String suffixText = suffix == 0 ? "" : "-" + suffix;
            backup = envFile.resolveSibling(envFile.getFileName() + ".backup-" + timestamp + suffixText);
            suffix++;
        } while (Files.exists(backup));
        return Files.copy(envFile, backup, StandardCopyOption.COPY_ATTRIBUTES);
    }

    /**
     * Reports creation of the runtime .env file without exposing template or secret values.
     *
     * @param envFile created .env file
     * @param source non-secret description of the source used to create it
     */
    public static void reportEnvFileCreated(Path envFile, String source) {
        String message = "env=" + absolutePath(envFile) + " event=created source=" + source;
        System.err.println("[Amsha Agent] .env created: " + message);
        appendChangeLog(envFile, message);
    }

    private static String valueState(String value) {
        if (value == null || value.isBlank()) {
            return "<unset>";
        }
        return isDisabledValue(value) ? "disabled" : "<set>";
    }

    private static boolean isDisabledValue(String value) {
        String normalized = value.trim();
        return normalized.equalsIgnoreCase("none")
                || normalized.equalsIgnoreCase("false")
                || normalized.equalsIgnoreCase("skip");
    }

    private static void reportChange(Path envFile, String key, String oldState, String newState) {
        String message = "env=" + absolutePath(envFile) + " key=" + key + " old=" + oldState + " new=" + newState;
        System.err.println("[Amsha Agent] .env changed: " + message);
        appendChangeLog(envFile, message);
    }

    private static String absolutePath(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private static void appendChangeLog(Path envFile, String message) {
        Path changeLog = envFile.resolveSibling(envFile.getFileName() + CHANGE_LOG_SUFFIX);
        String entry = Instant.now() + " " + message + System.lineSeparator();
        try {
            Files.writeString(changeLog, entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warnf("[Amsha Agent] Could not write .env change log: %s", LogUtil.rootMessage(e));
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
                System.err.println("[Amsha Agent] No .env found. Created .env.example next to the binary.");
            } catch (IOException e) {
                log.warnf("Failed to extract .env.example: %s", LogUtil.rootMessage(e));
            }
        }
    }
}
