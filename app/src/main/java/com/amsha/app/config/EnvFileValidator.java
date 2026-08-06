package com.amsha.app.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jboss.logging.Logger;

/**
 * Validates .env file structure and reports issues to the user.
 * Does NOT modify the .env file — only reports problems.
 *
 * <p>Checks performed:
 * <ul>
 *   <li>Duplicate keys (last-wins silently causes confusion)</li>
 *   <li>Malformed lines (missing =, invalid key names)</li>
 *   <li>Placeholder values (changeme, angle brackets, repeated-x)</li>
 *   <li>Empty values for security-sensitive keys</li>
 * </ul>
 */
public class EnvFileValidator {

    private static final Logger log = Logger.getLogger(EnvFileValidator.class);

    private static final Pattern ENV_PATTERN = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_.]*?)\\s*=\\s*(.*?)\\s*$");

    private static final Set<String> SECURITY_KEYS = Set.of(
            "AMSHA_VAULT_PEPPER",
            "AMSHA_AUDIT_HMAC_KEY",
            "AMSHA_API_KEYS_INTERNAL",
            "AMSHA_API_KEYS_CI",
            "AMSHA_API_KEYS_ADMIN",
            "AMSHA_AUTH_HMAC_KEY");

    private static final Set<String> API_KEY_KEYS = Set.of(
            "AMSHA_GROQ_API_KEY",
            "AMSHA_OPENAI_API_KEY",
            "AMSHA_OPENROUTER_API_KEY",
            "AMSHA_GEMINI_API_KEY",
            "AMSHA_NVIDIA_API_KEY",
            "AMSHA_CEREBRAS_API_KEY",
            "AMSHA_OMLX_API_KEY",
            "AMSHA_GENERIC_OPENAI_API_KEY",
            "AMSHA_EMBEDDING_JINA_API_KEY",
            "AMSHA_EMBEDDING_OPENAI_API_KEY",
            "TELEGRAM_BOT_TOKEN",
            "AMSHA_TOOLS_GITHUB_TOKEN",
            "AMSHA_TOOLS_SERPER_API_KEY",
            "AMSHA_TOOLS_BRAVE_API_KEY");

    /**
     * Result of validating a .env file.
     */
    public record ValidationResult(
            List<String> duplicateKeys,
            List<String> malformedLines,
            List<String> placeholderValues,
            List<String> emptySecurityKeys,
            List<String> emptyApiKeys,
            int totalKeys) {

        public boolean hasIssues() {
            return !duplicateKeys.isEmpty()
                    || !malformedLines.isEmpty()
                    || !placeholderValues.isEmpty()
                    || !emptySecurityKeys.isEmpty()
                    || !emptyApiKeys.isEmpty();
        }

        public int issueCount() {
            return duplicateKeys.size()
                    + malformedLines.size()
                    + placeholderValues.size()
                    + emptySecurityKeys.size()
                    + emptyApiKeys.size();
        }
    }

    /**
     * Validates the .env file at the given path and returns a result.
     * Does NOT modify the file.
     */
    public static ValidationResult validate(Path envFile) {
        List<String> duplicateKeys = new ArrayList<>();
        List<String> malformedLines = new ArrayList<>();
        List<String> placeholderValues = new ArrayList<>();
        List<String> emptySecurityKeys = new ArrayList<>();
        List<String> emptyApiKeys = new ArrayList<>();
        Map<String, Integer> keyCount = new HashMap<>();

        if (!Files.exists(envFile)) {
            return new ValidationResult(
                    duplicateKeys, malformedLines, placeholderValues, emptySecurityKeys, emptyApiKeys, 0);
        }

        try (BufferedReader reader = Files.newBufferedReader(envFile)) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                String trimmed = line.trim();

                // Skip empty lines and comments
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

                Matcher m = ENV_PATTERN.matcher(trimmed);
                if (!m.find()) {
                    malformedLines.add("Line " + lineNum + ": " + trimmed);
                    continue;
                }

                String key = m.group(1);
                String value = m.group(2).trim();

                // Strip inline comments (but not inside quotes)
                if (value.contains(" #") || value.contains("\t#")) {
                    if (!(value.startsWith("\"") || value.startsWith("'"))) {
                        int idxSpace = value.indexOf(" #");
                        int idxTab = value.indexOf("\t#");
                        int idx = (idxSpace != -1 && idxTab != -1)
                                ? Math.min(idxSpace, idxTab)
                                : (idxSpace != -1 ? idxSpace : idxTab);
                        if (idx != -1) {
                            value = value.substring(0, idx).trim();
                        }
                    }
                }

                // Strip surrounding quotes. A lone or unmatched opening quote is
                // malformed input, not an application-startup failure.
                if (value.startsWith("\"") || value.startsWith("'")) {
                    String quote = value.substring(0, 1);
                    if (value.length() < 2 || !value.endsWith(quote)) {
                        malformedLines.add("Line " + lineNum + ": " + trimmed);
                        continue;
                    }
                    value = value.substring(1, value.length() - 1);
                }

                // Track duplicates
                keyCount.merge(key, 1, Integer::sum);

                // Check for placeholder values
                if (isPlaceholder(value)) {
                    placeholderValues.add(key + "=" + value + " (line " + lineNum + ")");
                }

                // Check empty security keys
                if (SECURITY_KEYS.contains(key) && (value.isEmpty() || isPlaceholder(value))) {
                    emptySecurityKeys.add(key + " (line " + lineNum + ")");
                }

                // Check empty API keys
                if (API_KEY_KEYS.contains(key) && value.isEmpty()) {
                    emptyApiKeys.add(key + " (line " + lineNum + ")");
                }
            }

            // Collect duplicates
            for (Map.Entry<String, Integer> entry : keyCount.entrySet()) {
                if (entry.getValue() > 1) {
                    duplicateKeys.add(entry.getKey() + " appears " + entry.getValue() + " times");
                }
            }
        } catch (IOException e) {
            log.warnf("Failed to read .env for validation: %s", e.getMessage());
        }

        return new ValidationResult(
                duplicateKeys, malformedLines, placeholderValues, emptySecurityKeys, emptyApiKeys, keyCount.size());
    }

    /**
     * Prints a human-readable report of validation issues to stderr.
     * Only prints if there are issues.
     */
    public static void printReport(ValidationResult result, Path envFile) {
        if (!result.hasIssues()) return;

        System.err.println();
        System.err.println("╔══════════════════════════════════════════════════════════╗");
        System.err.println("║            .env Configuration Issues Detected            ║");
        System.err.println("╚══════════════════════════════════════════════════════════╝");
        System.err.println();
        System.err.println("  File: " + envFile.toAbsolutePath());
        System.err.println("  Total keys: " + result.totalKeys());
        System.err.println("  Issues found: " + result.issueCount());
        System.err.println();

        if (!result.duplicateKeys().isEmpty()) {
            System.err.println("  ⚠  DUPLICATE KEYS (last value wins silently):");
            for (String msg : result.duplicateKeys()) {
                System.err.println("     - " + msg);
            }
            System.err.println("     Fix: Remove duplicate entries, keep only the intended value.");
            System.err.println();
        }

        if (!result.malformedLines().isEmpty()) {
            System.err.println("  ⚠  MALFORMED LINES (not KEY=value format):");
            for (String msg : result.malformedLines()) {
                System.err.println("     - " + msg);
            }
            System.err.println("     Fix: Ensure each non-comment line has format KEY=value.");
            System.err.println();
        }

        if (!result.placeholderValues().isEmpty()) {
            System.err.println("  ⚠  PLACEHOLDER VALUES (will not work at runtime):");
            for (String msg : result.placeholderValues()) {
                System.err.println("     - " + msg);
            }
            System.err.println("     Fix: Replace placeholder with actual values.");
            System.err.println();
        }

        if (!result.emptySecurityKeys().isEmpty()) {
            System.err.println("  ❌  EMPTY/INVALID SECURITY KEYS (required for operation):");
            for (String msg : result.emptySecurityKeys()) {
                System.err.println("     - " + msg);
            }
            System.err.println("     Fix: Generate with: openssl rand -base64 32");
            System.err.println();
        }

        if (!result.emptyApiKeys().isEmpty()) {
            System.err.println("  ℹ  EMPTY API KEYS (optional, provider will not work without):");
            for (String msg : result.emptyApiKeys()) {
                System.err.println("     - " + msg);
            }
            System.err.println();
        }

        System.err.println("  " + "─".repeat(58));
        System.err.println();
    }

    private static boolean isPlaceholder(String value) {
        if (value == null || value.isEmpty()) return false;
        if (isExplicitOptOut(value)) return false;
        if (value.contains("changeme")) return true;
        if (value.contains("<")) return true;
        if (value.contains("your_") && value.contains("_here")) return true;
        if (value.equalsIgnoreCase("none")) return true;
        if (value.matches(".*x{8,}.*")) return true;
        if (value.equals("ABCdef")) return true;
        if (value.equals("mock-telegram-token")) return true;
        if (value.equals("key-internal-changeme")) return true;
        return false;
    }

    private static boolean isExplicitOptOut(String value) {
        return value.equalsIgnoreCase("none") || value.equalsIgnoreCase("false") || value.equalsIgnoreCase("skip");
    }
}
