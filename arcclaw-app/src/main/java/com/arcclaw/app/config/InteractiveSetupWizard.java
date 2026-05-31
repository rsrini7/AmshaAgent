package com.arcclaw.app.config;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.arcclaw.app.util.CliUtils;
import com.arcclaw.common.util.LogUtil;

import org.jboss.logging.Logger;

/**
 * Handles the interactive CLI setup wizard flow.
 */
public class InteractiveSetupWizard {

    private static final Logger log = Logger.getLogger(InteractiveSetupWizard.class);

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "ARCCLAW_GROQ_API_KEY",
            "ARCCLAW_OPENROUTER_API_KEY",
            "ARCCLAW_OPENAI_API_KEY",
            "ARCCLAW_GEMINI_API_KEY",
            "ARCCLAW_NVIDIA_API_KEY",
            "ARCCLAW_CEREBRAS_API_KEY",
            "ARCCLAW_OMLX_API_KEY",
            "ARCCLAW_EMBED_API_KEY",
            "ARCCLAW_VAULT_PEPPER",
            "ARCCLAW_AUDIT_HMAC_KEY",
            "ARCCLAW_API_KEYS_INTERNAL",
            "ARCCLAW_API_KEYS_CI",
            "TELEGRAM_BOT_TOKEN",
            "ARCCLAW_TOOLS_BRAVE_API_KEY",
            "ARCCLAW_TOOLS_SERPER_API_KEY",
            "ARCCLAW_TOOLS_GITHUB_TOKEN",
            "ARCCLAW_INTENT_CLASSIFIER_API_KEY",
            "ARCCLAW_COMPACTION_API_KEY");

    private static final Set<String> AUTO_GENERATABLE_KEYS = Set.of(
            "ARCCLAW_VAULT_PEPPER",
            "ARCCLAW_AUDIT_HMAC_KEY",
            "ARCCLAW_API_KEYS_INTERNAL",
            "ARCCLAW_API_KEYS_CI",
            "ARCCLAW_API_KEYS_ADMIN");

    public static void run(Path binaryDir, String[] args) {
        if (System.getProperty("ARCCLAW_LLM_PROVIDER") == null && System.getenv("ARCCLAW_LLM_PROVIDER") == null) {
            promptProviderSelection();
        }

        List<CliUtils.VarSpec> missing = CliUtils.getMissingVarSpecs(args);
        Path envFile = binaryDir.resolve(".env");

        if (!Files.exists(envFile)) {
            try (InputStream in = InteractiveSetupWizard.class.getResourceAsStream("/env-example")) {
                if (in != null) Files.copy(in, envFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                log.debugf("Failed to seed .env from example: %s", LogUtil.rootMessage(e));
            }
        }

        if (!missing.isEmpty()) {
            ensureBaseConfigsInEnv(envFile);

            System.err.println();
            System.err.println("╔══════════════════════════════════════════════════════════╗");
            System.err.println("║         ArcClaw - First-Run Interactive Setup            ║");
            System.err.println("╚══════════════════════════════════════════════════════════╝");
            System.err.println();
            System.err.println("  " + missing.size() + " required value(s) need configuration.");
            System.err.println("  Saved to: " + envFile);
            System.err.println("  Press Enter to skip a value (you can fill it in .env later).");
            System.err.println();

            boolean skipTelegram = false;
            boolean askedTelegram = false;

            if (shouldPromptUnifiedKey(missing)) {
                promptUnifiedKey(envFile, missing);
            }

            for (CliUtils.VarSpec spec : missing) {
                if (isTelegramKey(spec.key())) {
                    if (!askedTelegram) {
                        skipTelegram = !promptTelegramEnablement(envFile);
                        askedTelegram = true;
                    }
                    if (skipTelegram) continue;
                }

                if (spec.key().equals("ARCCLAW_TOOLS_GITHUB_TOKEN")
                        && !promptOptionalModule(spec.key(), "GitHub repository access")) continue;
                if (spec.key().equals("ARCCLAW_TOOLS_SERPER_API_KEY")
                        && !promptOptionalModule(spec.key(), "Serper Search Integration")) continue;
                if (spec.key().equals("ARCCLAW_TOOLS_BRAVE_API_KEY")
                        && !promptOptionalModule(spec.key(), "Brave Search Integration")) continue;

                promptVariable(envFile, spec);
            }
        }
    }

    private static void promptProviderSelection() {
        System.err.println();
        System.err.println("  ┌─ LLM Provider Select");
        System.err.println("  │  1. Groq (default) - ultra fast, free tier available");
        System.err.println("  │  2. OpenRouter - access any model (Llama 3, Claude, etc.)");
        System.err.println("  │  3. OpenAI - direct connection");
        System.err.println("  │  4. Ollama - local only (no API key required)");
        System.err.println("  │  5. NVIDIA - high performance NIMs");
        System.err.println("  │  6. Gemini - Google AI (free tier available)");
        System.err.println("  │  7. Cerebras - Ultra-fast LPU inference");
        System.err.println("  │  8. OMLX - Local MLX-based server (no API key needed)");
        System.err.println("  │");
        String choice = readLineWithFallback(System.console(), "  └─ Select provider [1-8, Default: 1]: ");
        String provider =
                switch (choice != null ? choice.trim() : "") {
                    case "2" -> "openrouter";
                    case "3" -> "openai";
                    case "4" -> "ollama";
                    case "5" -> "nvidia";
                    case "6" -> "gemini";
                    case "7" -> "cerebras";
                    case "8" -> "omlx";
                    default -> "groq";
                };
        System.setProperty("ARCCLAW_LLM_PROVIDER", provider);
        System.setProperty("ARCCLAW_COMPACTION_PROVIDER", provider);
        System.setProperty("ARCCLAW_INTENT_CLASSIFIER_PROVIDER", provider);
    }

    private static void ensureBaseConfigsInEnv(Path envFile) {
        EnvConfigInitializer.patchEnvFile(
                envFile,
                "ARCCLAW_LLM_PROVIDER",
                System.getProperty("ARCCLAW_LLM_PROVIDER", "groq"),
                EnvConfigInitializer.PatchMode.UPDATE);
    }

    private static boolean shouldPromptUnifiedKey(List<CliUtils.VarSpec> missing) {
        String llmKeyName = llmApiKeyForProvider(System.getProperty("ARCCLAW_LLM_PROVIDER", "groq"));
        boolean llmKeyMissing = missing.stream().anyMatch(s -> s.key().equals(llmKeyName));
        boolean intentKeyMissing = missing.stream().anyMatch(s -> s.key().equals("ARCCLAW_INTENT_CLASSIFIER_API_KEY"));
        boolean compactionKeyMissing = missing.stream().anyMatch(s -> s.key().equals("ARCCLAW_COMPACTION_API_KEY"));
        return llmKeyMissing && (intentKeyMissing || compactionKeyMissing);
    }

    private static void promptUnifiedKey(Path envFile, List<CliUtils.VarSpec> missing) {
        System.err.println("  ┌─ Unified API Key Setup");
        System.err.println("  │  ArcClaw uses LLMs for assistant, intent classifier, and compaction.");
        String resp = readLineWithFallback(System.console(), "  └─ Use a single key for all? [Y/n]: ");
        if (resp == null
                || resp.trim().isEmpty()
                || resp.trim().toLowerCase(Locale.ROOT).startsWith("y")) {
            String provider = System.getProperty("ARCCLAW_LLM_PROVIDER", "groq");
            String key = readPasswordWithFallback(
                    System.console(), "  └─ Enter " + provider.toUpperCase(Locale.ROOT) + " API key: ");
            if (key != null && !key.isBlank()) {
                applyUnifiedKey(envFile, key, provider);
                String llmKeyName = llmApiKeyForProvider(provider);
                missing.removeIf(s -> s.key().equals(llmKeyName)
                        || s.key().equals("ARCCLAW_INTENT_CLASSIFIER_API_KEY")
                        || s.key().equals("ARCCLAW_COMPACTION_API_KEY"));
            }
        }
    }

    private static void applyUnifiedKey(Path envFile, String key, String provider) {
        String llmKeyName = llmApiKeyForProvider(provider);
        String[] keys = {llmKeyName, "ARCCLAW_INTENT_CLASSIFIER_API_KEY", "ARCCLAW_COMPACTION_API_KEY"};
        for (String k : keys) {
            System.setProperty(k, key);
            EnvConfigInitializer.patchEnvFile(envFile, k, key, EnvConfigInitializer.PatchMode.UPDATE);
        }
        System.err.println("     ✓ Unified key saved.");
    }

    private static String llmApiKeyForProvider(String provider) {
        return switch (provider.toLowerCase(Locale.ROOT)) {
            case "openrouter" -> "ARCCLAW_OPENROUTER_API_KEY";
            case "openai" -> "ARCCLAW_OPENAI_API_KEY";
            case "gemini" -> "ARCCLAW_GEMINI_API_KEY";
            case "cerebras" -> "ARCCLAW_CEREBRAS_API_KEY";
            case "nvidia" -> "ARCCLAW_NVIDIA_API_KEY";
            case "omlx" -> "ARCCLAW_OMLX_API_KEY";
            default -> "ARCCLAW_GROQ_API_KEY";
        };
    }

    private static boolean promptTelegramEnablement(Path envFile) {
        System.err.println("  ┌─ Telegram Integration");
        String response = readLineWithFallback(System.console(), "  └─ Enable Telegram? [y/N]: ");
        boolean enabled =
                response != null && response.trim().toLowerCase(Locale.ROOT).startsWith("y");
        EnvConfigInitializer.patchEnvFile(
                envFile, "ARCCLAW_TELEGRAM_ENABLED", String.valueOf(enabled), EnvConfigInitializer.PatchMode.UPDATE);
        System.setProperty("ARCCLAW_TELEGRAM_ENABLED", String.valueOf(enabled));
        return enabled;
    }

    private static boolean promptOptionalModule(String key, String label) {
        System.err.println("  ┌─ " + label);
        String response = readLineWithFallback(System.console(), "  └─ Configure " + key + "? [y/N]: ");
        return response != null && response.trim().toLowerCase(Locale.ROOT).startsWith("y");
    }

    private static void promptVariable(Path envFile, CliUtils.VarSpec spec) {
        System.err.println("  ┌─ " + spec.key());
        System.err.println("  │  " + spec.description());
        String prompt = "  └─ Enter value"
                + (AUTO_GENERATABLE_KEYS.contains(spec.key()) ? " (Enter to auto-generate): " : ": ");
        String value = SENSITIVE_KEYS.contains(spec.key())
                ? readPasswordWithFallback(System.console(), prompt)
                : readLineWithFallback(System.console(), prompt);

        if (value == null || value.isBlank()) {
            if (AUTO_GENERATABLE_KEYS.contains(spec.key())) {
                value = generateRandomValue();
                System.err.println("     ✓ Auto-generated");
            } else {
                System.err.println("     [skipped]");
                return;
            }
        }
        EnvConfigInitializer.patchEnvFile(envFile, spec.key(), value, EnvConfigInitializer.PatchMode.UPDATE);
        System.setProperty(spec.key(), value);
        System.err.println("     ✓ Saved");
    }

    private static boolean isTelegramKey(String key) {
        return key.startsWith("TELEGRAM_") || key.startsWith("ARCCLAW_TELEGRAM_");
    }

    private static final SecureRandom RNG = new SecureRandom();

    private static String generateRandomValue() {
        byte[] bytes = new byte[32];
        RNG.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String readLineWithFallback(Console console, String prompt) {
        if (console != null) return console.readLine(prompt);
        System.err.print(prompt);
        System.err.flush();
        try {
            return new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private static String readPasswordWithFallback(Console console, String prompt) {
        if (console != null) {
            char[] pw = console.readPassword(prompt);
            return pw != null ? new String(pw).trim() : "";
        }
        return readLineWithFallback(null, prompt);
    }
}
