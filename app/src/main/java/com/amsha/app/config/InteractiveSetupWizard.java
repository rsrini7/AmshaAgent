package com.amsha.app.config;

import java.io.ByteArrayOutputStream;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.amsha.app.util.CliUtils;
import com.amsha.app.util.VarSpec;
import com.amsha.common.util.LogUtil;

import org.jboss.logging.Logger;

/**
 * Handles the interactive CLI setup wizard flow.
 */
@SuppressWarnings({"java:S1192", "java:S106"})
public class InteractiveSetupWizard {

    private static final Logger log = Logger.getLogger(InteractiveSetupWizard.class);

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "AMSHA_GROQ_API_KEY",
            "AMSHA_OPENROUTER_API_KEY",
            "AMSHA_OPENAI_API_KEY",
            "AMSHA_GEMINI_API_KEY",
            "AMSHA_NVIDIA_API_KEY",
            "AMSHA_CEREBRAS_API_KEY",
            "AMSHA_OMLX_API_KEY",
            "AMSHA_EMBEDDING_JINA_API_KEY",
            "AMSHA_EMBEDDING_OPENAI_API_KEY",
            "AMSHA_VAULT_PEPPER",
            "AMSHA_AUDIT_HMAC_KEY",
            "AMSHA_API_KEYS_INTERNAL",
            "AMSHA_API_KEYS_CI",
            "TELEGRAM_BOT_TOKEN",
            "AMSHA_TOOLS_BRAVE_API_KEY",
            "AMSHA_TOOLS_SERPER_API_KEY",
            "AMSHA_TOOLS_GITHUB_TOKEN",
            "AMSHA_COMPACTION_GROQ_API_KEY",
            "AMSHA_COMPACTION_CEREBRAS_API_KEY",
            "AMSHA_COMPACTION_OPENROUTER_API_KEY",
            "AMSHA_COMPACTION_GENERIC_OPENAI_API_KEY",
            "AMSHA_CLASSIFIER_GROQ_API_KEY",
            "AMSHA_CLASSIFIER_CEREBRAS_API_KEY",
            "AMSHA_CLASSIFIER_OPENROUTER_API_KEY",
            "AMSHA_CLASSIFIER_GENERIC_OPENAI_API_KEY");

    private static final Set<String> AUTO_GENERATABLE_KEYS = Set.of(
            "AMSHA_VAULT_PEPPER",
            "AMSHA_AUDIT_HMAC_KEY",
            "AMSHA_API_KEYS_INTERNAL",
            "AMSHA_API_KEYS_CI",
            "AMSHA_API_KEYS_ADMIN");

    public static void run(Path binaryDir, String[] args) {
        if (System.getProperty("AMSHA_LLM_PROVIDER") == null && System.getenv("AMSHA_LLM_PROVIDER") == null) {
            promptProviderSelection();
        }

        List<VarSpec> missing = CliUtils.getMissingVarSpecs(args);
        Path envFile = binaryDir.resolve(".env");

        if (!Files.exists(envFile)) {
            try (InputStream in = InteractiveSetupWizard.class.getResourceAsStream("/env-example")) {
                if (in != null) {
                    Files.copy(in, envFile, StandardCopyOption.REPLACE_EXISTING);
                    EnvConfigInitializer.reportEnvFileCreated(envFile, "bundled .env.example template");
                }
            } catch (IOException e) {
                log.debugf("Failed to seed .env from example: %s", LogUtil.rootMessage(e));
            }
        }

        if (!missing.isEmpty()) {
            ensureBaseConfigsInEnv(envFile);

            System.err.println();
            System.err.println("╔══════════════════════════════════════════════════════════╗");
            System.err.println("║         Amsha Agent - First-Run Interactive Setup        ║");
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

            for (VarSpec spec : missing) {
                if (isTelegramKey(spec.key())) {
                    if (!askedTelegram) {
                        skipTelegram = !promptTelegramEnablement(envFile);
                        askedTelegram = true;
                    }
                    if (skipTelegram) continue;
                }

                if (spec.key().equals("AMSHA_TOOLS_GITHUB_TOKEN")
                        && !promptOptionalModule(envFile, spec.key(), "GitHub repository access")) continue;
                if (spec.key().equals("AMSHA_TOOLS_SERPER_API_KEY")
                        && !promptOptionalModule(envFile, spec.key(), "Serper Search Integration")) continue;
                if (spec.key().equals("AMSHA_TOOLS_BRAVE_API_KEY")
                        && !promptOptionalModule(envFile, spec.key(), "Brave Search Integration")) continue;

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
        System.err.println("  │  9. Generic OpenAI - any OpenAI-compatible endpoint");
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
                    case "9" -> "generic-openai";
                    default -> "groq";
                };
        System.setProperty("AMSHA_LLM_PROVIDER", provider);
        System.setProperty("AMSHA_INFINITE_LOOP_LLM_PROVIDERS", provider);
        System.setProperty("AMSHA_INFINITE_LOOP_COMPACTION_PROVIDERS", provider);
        System.setProperty("AMSHA_INFINITE_LOOP_CLASSIFIER_PROVIDERS", provider);
    }

    private static void ensureBaseConfigsInEnv(Path envFile) {
        String provider = Optional.ofNullable(System.getProperty("AMSHA_LLM_PROVIDER"))
                .or(() -> Optional.ofNullable(System.getenv("AMSHA_LLM_PROVIDER")))
                .orElse("groq");
        EnvConfigInitializer.patchEnvFile(
                envFile, "AMSHA_LLM_PROVIDER", provider, EnvConfigInitializer.PatchMode.UPDATE);
        boolean infiniteLoop = provider.equalsIgnoreCase("infinite-loop");
        patchProviderList(
                envFile,
                "AMSHA_INFINITE_LOOP_LLM_PROVIDERS",
                infiniteLoop
                        ? configuredProviderList("AMSHA_INFINITE_LOOP_LLM_PROVIDERS", "omlx,groq,openrouter")
                        : provider);
        patchProviderList(
                envFile,
                "AMSHA_INFINITE_LOOP_COMPACTION_PROVIDERS",
                infiniteLoop
                        ? configuredProviderList("AMSHA_INFINITE_LOOP_COMPACTION_PROVIDERS", "groq,ollama")
                        : provider);
        patchProviderList(
                envFile,
                "AMSHA_INFINITE_LOOP_CLASSIFIER_PROVIDERS",
                infiniteLoop
                        ? configuredProviderList("AMSHA_INFINITE_LOOP_CLASSIFIER_PROVIDERS", "groq,ollama")
                        : provider);
    }

    private static void patchProviderList(Path envFile, String key, String value) {
        EnvConfigInitializer.patchEnvFile(envFile, key, value, EnvConfigInitializer.PatchMode.UPDATE);
        System.setProperty(key, value);
    }

    private static String configuredProviderList(String key, String fallback) {
        String configured = Optional.ofNullable(System.getProperty(key))
                .or(() -> Optional.ofNullable(System.getenv(key)))
                .orElse("");
        String providers = Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .filter(value -> !value.equalsIgnoreCase("infinite-loop"))
                .collect(Collectors.joining(","));
        return providers.isBlank() ? fallback : providers;
    }

    private static boolean shouldPromptUnifiedKey(List<VarSpec> missing) {
        String provider = System.getProperty("AMSHA_LLM_PROVIDER", "groq").toLowerCase(Locale.ROOT);
        String llmKeyName = llmApiKeyForProvider(provider);
        boolean llmKeyMissing = missing.stream().anyMatch(s -> s.key().equals(llmKeyName));
        String compactionKey = compactionApiKeyForProvider(provider);
        String classifierKey = classifierApiKeyForProvider(provider);
        boolean compactionKeyMissing =
                compactionKey != null && missing.stream().anyMatch(s -> s.key().equals(compactionKey));
        boolean classifierKeyMissing =
                classifierKey != null && missing.stream().anyMatch(s -> s.key().equals(classifierKey));
        return llmKeyMissing && (compactionKeyMissing || classifierKeyMissing);
    }

    private static void promptUnifiedKey(Path envFile, List<VarSpec> missing) {
        System.err.println("  ┌─ Unified API Key Setup");
        System.err.println("  │  Amsha Agent uses LLMs for assistant, intent classifier, and compaction.");
        String resp = readLineWithFallback(System.console(), "  └─ Use a single key for all? [Y/n]: ");
        if (resp == null
                || resp.trim().isEmpty()
                || resp.trim().toLowerCase(Locale.ROOT).startsWith("y")) {
            String provider = System.getProperty("AMSHA_LLM_PROVIDER", "groq").toLowerCase(Locale.ROOT);
            String key = readPasswordWithFallback(
                    System.console(), "  └─ Enter " + provider.toUpperCase(Locale.ROOT) + " API key: ");
            if (key != null && !key.isBlank()) {
                applyUnifiedKey(envFile, key, provider);
                String llmKeyName = llmApiKeyForProvider(provider);
                String compactionKey = compactionApiKeyForProvider(provider);
                String classifierKey = classifierApiKeyForProvider(provider);
                missing.removeIf(s -> s.key().equals(llmKeyName)
                        || (compactionKey != null && s.key().equals(compactionKey))
                        || (classifierKey != null && s.key().equals(classifierKey)));
            }
        }
    }

    private static void applyUnifiedKey(Path envFile, String key, String provider) {
        String llmKeyName = llmApiKeyForProvider(provider);
        String compactionKey = compactionApiKeyForProvider(provider);
        String classifierKey = classifierApiKeyForProvider(provider);
        EnvConfigInitializer.patchEnvFile(envFile, llmKeyName, key, EnvConfigInitializer.PatchMode.UPDATE);
        System.setProperty(llmKeyName, key);
        if (compactionKey != null) {
            EnvConfigInitializer.patchEnvFile(envFile, compactionKey, key, EnvConfigInitializer.PatchMode.UPDATE);
            System.setProperty(compactionKey, key);
        }
        if (classifierKey != null) {
            EnvConfigInitializer.patchEnvFile(envFile, classifierKey, key, EnvConfigInitializer.PatchMode.UPDATE);
            System.setProperty(classifierKey, key);
        }
        System.err.println("     ✓ Unified key saved.");
    }

    private static String llmApiKeyForProvider(String provider) {
        return switch (provider.toLowerCase(Locale.ROOT)) {
            case "openrouter" -> "AMSHA_OPENROUTER_API_KEY";
            case "openai" -> "AMSHA_OPENAI_API_KEY";
            case "gemini" -> "AMSHA_GEMINI_API_KEY";
            case "cerebras" -> "AMSHA_CEREBRAS_API_KEY";
            case "nvidia" -> "AMSHA_NVIDIA_API_KEY";
            case "omlx" -> "AMSHA_OMLX_API_KEY";
            case "generic-openai" -> "AMSHA_GENERIC_OPENAI_API_KEY";
            default -> "AMSHA_GROQ_API_KEY";
        };
    }

    /**
     * Returns the per-provider env-var name for the compaction slot's API key,
     * or {@code null} if the provider does not require an API key (e.g. ollama, omlx).
     */
    private static String compactionApiKeyForProvider(String provider) {
        return switch (provider.toLowerCase(Locale.ROOT)) {
            case "ollama", "omlx" -> null;
            case "openrouter" -> "AMSHA_COMPACTION_OPENROUTER_API_KEY";
            case "openai" -> "AMSHA_COMPACTION_OPENAI_API_KEY";
            case "gemini" -> "AMSHA_COMPACTION_GEMINI_API_KEY";
            case "cerebras" -> "AMSHA_COMPACTION_CEREBRAS_API_KEY";
            case "nvidia" -> "AMSHA_COMPACTION_NVIDIA_API_KEY";
            case "generic-openai" -> "AMSHA_COMPACTION_GENERIC_OPENAI_API_KEY";
            default -> "AMSHA_COMPACTION_GROQ_API_KEY";
        };
    }

    /**
     * Returns the per-provider env-var name for the intent-classifier slot's API key,
     * or {@code null} if the provider does not require an API key (e.g. ollama, omlx).
     */
    private static String classifierApiKeyForProvider(String provider) {
        return switch (provider.toLowerCase(Locale.ROOT)) {
            case "ollama", "omlx" -> null;
            case "openrouter" -> "AMSHA_CLASSIFIER_OPENROUTER_API_KEY";
            case "openai" -> "AMSHA_CLASSIFIER_OPENAI_API_KEY";
            case "gemini" -> "AMSHA_CLASSIFIER_GEMINI_API_KEY";
            case "cerebras" -> "AMSHA_CLASSIFIER_CEREBRAS_API_KEY";
            case "nvidia" -> "AMSHA_CLASSIFIER_NVIDIA_API_KEY";
            case "generic-openai" -> "AMSHA_CLASSIFIER_GENERIC_OPENAI_API_KEY";
            default -> "AMSHA_CLASSIFIER_GROQ_API_KEY";
        };
    }

    private static boolean promptTelegramEnablement(Path envFile) {
        System.err.println("  ┌─ Telegram Integration");
        String response = readLineWithFallback(System.console(), "  └─ Enable Telegram? [y/N]: ");
        boolean enabled =
                response != null && response.trim().toLowerCase(Locale.ROOT).startsWith("y");
        EnvConfigInitializer.patchEnvFile(
                envFile, "AMSHA_TELEGRAM_ENABLED", String.valueOf(enabled), EnvConfigInitializer.PatchMode.UPDATE);
        System.setProperty("AMSHA_TELEGRAM_ENABLED", String.valueOf(enabled));
        return enabled;
    }

    private static boolean promptOptionalModule(Path envFile, String key, String label) {
        System.err.println("  ┌─ " + label);
        String response = readLineWithFallback(System.console(), "  └─ Configure " + key + "? [y/N]: ");
        boolean enabled =
                response != null && response.trim().toLowerCase(Locale.ROOT).startsWith("y");
        if (!enabled) {
            EnvConfigInitializer.patchEnvFile(envFile, key, "none", EnvConfigInitializer.PatchMode.UPDATE);
            System.setProperty(key, "none");
            System.err.println(" ✓ Disabled; saved explicit opt-out.");
        }
        return enabled;
    }

    private static void promptVariable(Path envFile, VarSpec spec) {
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
        return key.startsWith("TELEGRAM_") || key.startsWith("AMSHA_TELEGRAM_");
    }

    @SuppressWarnings("java:S2119")
    private static String generateRandomValue() {
        byte[] bytes = new byte[32];
        // Instantiate SecureRandom locally — NOT as a static field.
        // GraalVM native image marks java.security.SecureRandom for run-time
        // initialization. A static field would pin the instance into the image
        // heap at build time, causing:
        //   UnsupportedFeatureException: An object of type 'java.security.SecureRandom'
        //   was found in the image heap.
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String readLineWithFallback(Console console, String prompt) {
        if (console != null) return console.readLine(prompt);
        System.err.print(prompt);
        System.err.flush();
        try (ByteArrayOutputStream line = new ByteArrayOutputStream()) {
            int next;
            while ((next = System.in.read()) != -1 && next != '\n') {
                if (next != '\r') line.write(next);
            }
            return next == -1 && line.size() == 0 ? null : line.toString(StandardCharsets.UTF_8);
        } catch (IOException _) {
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
