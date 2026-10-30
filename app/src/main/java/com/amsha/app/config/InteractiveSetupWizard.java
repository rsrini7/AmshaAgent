package com.amsha.app.config;

import java.io.ByteArrayOutputStream;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.amsha.app.cli.CliArguments;
import com.amsha.app.util.CliUtils;
import com.amsha.app.util.VarSpec;
import com.amsha.common.config.ConfigKeys;
import com.amsha.common.util.LogUtil;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.security.api.VaultConstants;

import org.jboss.logging.Logger;

/**
 * Handles the interactive CLI setup wizard flow.
 */
@SuppressWarnings({"java:S1192", "java:S106"})
public class InteractiveSetupWizard {

    private static final Logger log = Logger.getLogger(InteractiveSetupWizard.class);
    private static final String YES_RESPONSE_PREFIX = "y";
    private static final String TELEGRAM_ENV_PREFIX = "TELEGRAM_";
    private static final String AMSHA_TELEGRAM_ENV_PREFIX = "AMSHA_TELEGRAM_";

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            ConfigKeys.GROQ_API_KEY,
            ConfigKeys.OPENROUTER_API_KEY,
            ConfigKeys.OPENAI_API_KEY,
            ConfigKeys.GEMINI_API_KEY,
            ConfigKeys.NVIDIA_API_KEY,
            ConfigKeys.CEREBRAS_API_KEY,
            ConfigKeys.OMLX_API_KEY,
            ConfigKeys.GNANI_API_KEY,
            ConfigKeys.EMBEDDING_JINA_API_KEY,
            ConfigKeys.EMBEDDING_OPENAI_API_KEY,
            VaultConstants.VAULT_PEPPER,
            VaultConstants.AUDIT_HMAC_KEY,
            VaultConstants.AUTH_HMAC_KEY,
            VaultConstants.API_KEYS_INTERNAL,
            VaultConstants.API_KEYS_CI,
            ConfigKeys.TELEGRAM_BOT_TOKEN,
            VaultConstants.TOOLS_BRAVE_API_KEY,
            VaultConstants.TOOLS_SERPER_API_KEY,
            VaultConstants.TOOLS_GITHUB_TOKEN,
            ConfigKeys.COMPACTION_GROQ_API_KEY,
            ConfigKeys.COMPACTION_CEREBRAS_API_KEY,
            ConfigKeys.COMPACTION_OPENROUTER_API_KEY,
            ConfigKeys.COMPACTION_SARVAM_API_KEY,
            ConfigKeys.COMPACTION_OPENCODE_API_KEY,
            ConfigKeys.COMPACTION_GENERIC_OPENAI_API_KEY,
            ConfigKeys.CLASSIFIER_GROQ_API_KEY,
            ConfigKeys.CLASSIFIER_CEREBRAS_API_KEY,
            ConfigKeys.CLASSIFIER_OPENROUTER_API_KEY,
            ConfigKeys.CLASSIFIER_SARVAM_API_KEY,
            ConfigKeys.CLASSIFIER_OPENCODE_API_KEY,
            ConfigKeys.CLASSIFIER_GENERIC_OPENAI_API_KEY,
            ConfigKeys.SARVAM_API_KEY,
            ConfigKeys.OPENCODE_API_KEY,
            ConfigKeys.DECISION_TYPESAFE_API_KEY,
            ConfigKeys.DECISION_CLM_API_KEY,
            ConfigKeys.DECISION_LAYA_API_KEY,
            ConfigKeys.DECISION_KEV_API_KEY,
            ConfigKeys.DECISION_OMLX_API_KEY);

    private static final Set<String> AUTO_GENERATABLE_KEYS = Set.of(
            VaultConstants.VAULT_PEPPER,
            VaultConstants.AUDIT_HMAC_KEY,
            VaultConstants.AUTH_HMAC_KEY,
            VaultConstants.API_KEYS_INTERNAL,
            VaultConstants.API_KEYS_CI,
            VaultConstants.API_KEYS_ADMIN);

    public static void run(Path binaryDir, String[] args) {
        if (args != null && args.length > 0 && CliArguments.COMMAND_MODELS.equalsIgnoreCase(args[0])) {
            return;
        }

        if (configuredProvider().isBlank()) {
            promptProviderSelection();
        }

        List<VarSpec> missing = CliUtils.getMissingVarSpecs(args);
        Path envFile = binaryDir.resolve(".env");

        if (!Files.exists(envFile)) {
            try (InputStream in = InteractiveSetupWizard.class.getResourceAsStream("/env-example")) {
                if (in != null) {
                    Files.copy(in, envFile);
                    EnvConfigInitializer.reportEnvFileCreated(envFile, "bundled .env.example template");
                }
            } catch (IOException e) {
                log.debugf("Failed to seed .env from example: %s", LogUtil.rootMessage(e));
            }
        }

        ensureBaseConfigsInEnv(envFile);
        if (!missing.isEmpty()) {

            System.err.println();
            System.err.println("╔══════════════════════════════════════════════════════════╗");
            System.err.println("║         Amsha Agent - First-Run Interactive Setup        ║");
            System.err.println("╚══════════════════════════════════════════════════════════╝");
            System.err.println();
            System.err.println("  " + missing.size() + " required value(s) need configuration.");
            System.err.println("  Saved to: " + envFile);
            System.err.println("  Press Enter to skip a value (you can fill it in .env later).");
            System.err.println();

            printGitHubDocumentationSetupGuide();
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

                if (spec.key().equals(VaultConstants.TOOLS_GITHUB_TOKEN)
                        && !promptOptionalModule(envFile, spec.key(), "GitHub repository access")) continue;
                if (spec.key().equals(VaultConstants.TOOLS_SERPER_API_KEY)
                        && !promptOptionalModule(envFile, spec.key(), "Serper Search Integration")) continue;
                if (spec.key().equals(VaultConstants.TOOLS_BRAVE_API_KEY)
                        && !promptOptionalModule(envFile, spec.key(), "Brave Search Integration")) continue;

                if (spec.key().equals(VaultConstants.VAULT_PEPPER)) {
                    System.err.println(
                            "  Note: the vault pepper decrypts stored secrets. Keep the original when reusing a data directory.");
                }
                promptVariable(envFile, spec);
            }
        }
    }

    static void printGitHubDocumentationSetupGuide() {
        System.err.println("  Optional: GitHub documentation connector");
        System.err.println("  This indexes approved Markdown for private, workspace-scoped search.");
        System.err.println("  It is separate from AMSHA_TOOLS_GITHUB_TOKEN (the GitHub tool).");
        System.err.println("  Create a GitHub App with Contents: Read-only and Metadata: Read-only.");
        System.err.println("  Homepage URL: https://github.com/rsrini7/AmshaAgent");
        System.err.println("  Callback URL and Setup URL: leave blank; Amsha does not use OAuth.");
        System.err.println("  Request user authorization (OAuth): leave unchecked.");
        System.err.println("  Expire user authorization tokens: keep GitHub's default checked.");
        System.err.println("  Webhooks: turn Active off; leave URL and secret blank.");
        System.err.println("  Install scope: Only on this account (@rsrini7), then select only");
        System.err.println("  rsrini7/AmshaAgent when GitHub asks which repositories to install on.");
        System.err.println("  App ID is the numeric value labeled App ID on the App's General page.");
        System.err.println("  It is not your username, repository name, Client ID, Client secret,");
        System.err.println("  or installation ID. Amsha uses it with the private key");
        System.err.println("  to request a short-lived token for the selected repository.");
        System.err.println("  Set AMSHA_GITHUB_APP_ID=<numeric-app-id> in this .env file, then restart.");
        System.err.println("  Connector is disabled by default. To opt in, also set:");
        System.err.println("  AMSHA_GITHUB_DOCS_CONNECTOR_ENABLED=true, then restart Amsha.");
        System.err.println("  Upload the generated private key using the internal connector credential");
        System.err.println("  endpoint; never paste it into .env or this prompt.");
        System.err.println("  After deleting the SQLite data directory, restore the key with:");
        System.err.println("  mise run github:app-credential-upload -- \\");
        System.err.println("    --key-file \"$HOME/.config/amsha/secrets/amsha-github-app.pem\" \\");
        System.err.println("    --env-file dist/.env (replace with your server .env path).");
        System.err.println("  Details: docs/GITHUB_DOCUMENTATION_CONNECTOR.md");
    }

    private static void promptProviderSelection() {
        System.err.println();
        System.err.println("  ┌─ LLM Provider Select");
        System.err.println("  │  1. Groq - hosted API provider");
        System.err.println("  │  2. OpenRouter - access any model (Llama 3, Claude, etc.)");
        System.err.println("  │  3. OpenAI - direct connection");
        System.err.println("  │  4. Ollama - local only (no API key required)");
        System.err.println("  │  5. NVIDIA - high performance NIMs");
        System.err.println("  │  6. Gemini - Google AI (free tier available)");
        System.err.println("  │  7. Cerebras - Ultra-fast LPU inference");
        System.err.println("  │  8. OMLX - Local MLX-based server (no API key needed)");
        System.err.println("  │  9. Generic OpenAI - any OpenAI-compatible endpoint");
        System.err.println("  │  10. Sarvam - Indic-language models");
        System.err.println("  │  11. OpenCode - OpenAI-compatible endpoint (Zen / Go)");
        System.err.println("  │  12. Hugging Face - Inference API / Router");
        System.err.println("  │  13. Mistral AI - Codestral & Mistral models");
        System.err.println("  │  14. Cloudflare Workers AI - Serverless inference");
        System.err.println("  │  15. Cohere - Command R+ & Enterprise models");
        System.err.println("  │  16. Codex CLI - ChatGPT Plus OAuth (zero-config)");
        CliLlmProviderMenu.printAdditionalProviders();
        System.err.println("  │");
        String choice = readLineWithFallback(System.console(), "  └─ Select provider [1-21, no default]: ");
        String provider = CliLlmProviderMenu.providerForChoice(choice);
        if (provider.isBlank()) {
            System.err.println(
                    "[Amsha Agent] No provider selected. Choose one in the menu or set AMSHA_LLM_PROVIDER in .env.");
            return;
        }
        System.setProperty(ConfigKeys.LLM_PROVIDER, provider);
        if (!ModelDefaults.PROVIDER_INFINITE_LOOP.equalsIgnoreCase(provider)) {
            System.setProperty(ConfigKeys.AMSHA_INFINITE_LOOP_LLM_PROVIDERS, provider);
            System.setProperty(ConfigKeys.AMSHA_INFINITE_LOOP_COMPACTION_PROVIDERS, provider);
            System.setProperty(ConfigKeys.AMSHA_INFINITE_LOOP_CLASSIFIER_PROVIDERS, provider);
        }
    }

    private static void ensureBaseConfigsInEnv(Path envFile) {
        String provider = configuredProvider();
        if (provider.isBlank()) return;
        EnvConfigInitializer.patchEnvFile(
                envFile, ConfigKeys.LLM_PROVIDER, provider, EnvConfigInitializer.PatchMode.UPDATE);
        boolean infiniteLoop = ModelDefaults.PROVIDER_INFINITE_LOOP.equalsIgnoreCase(provider);
        patchProviderList(
                envFile,
                ConfigKeys.AMSHA_INFINITE_LOOP_LLM_PROVIDERS,
                infiniteLoop
                        ? configuredProviderList(ConfigKeys.AMSHA_INFINITE_LOOP_LLM_PROVIDERS, "omlx,ollama")
                        : provider);
        patchProviderList(
                envFile,
                ConfigKeys.AMSHA_INFINITE_LOOP_COMPACTION_PROVIDERS,
                infiniteLoop
                        ? configuredProviderList(ConfigKeys.AMSHA_INFINITE_LOOP_COMPACTION_PROVIDERS, "omlx,ollama")
                        : provider);
        patchProviderList(
                envFile,
                ConfigKeys.AMSHA_INFINITE_LOOP_CLASSIFIER_PROVIDERS,
                infiniteLoop
                        ? configuredProviderList(ConfigKeys.AMSHA_INFINITE_LOOP_CLASSIFIER_PROVIDERS, "omlx,ollama")
                        : provider);
        if (ModelDefaults.PROVIDER_SARVAM.equalsIgnoreCase(provider)) {
            patchBaseConfig(envFile, "AMSHA_COMPACTION_SARVAM_MODEL", "sarvam-105b-conversations");
            patchBaseConfig(envFile, "AMSHA_COMPACTION_SARVAM_BASE_URL", "https://api.sarvam.ai/v1");
            patchBaseConfig(envFile, "AMSHA_CLASSIFIER_SARVAM_MODEL", "sarvam-105b-conversations");
            patchBaseConfig(envFile, "AMSHA_CLASSIFIER_SARVAM_BASE_URL", "https://api.sarvam.ai/v1");
        }
        if (ModelDefaults.PROVIDER_OPENCODE.equalsIgnoreCase(provider)) {
            patchBaseConfig(envFile, "AMSHA_COMPACTION_OPENCODE_MODEL", "deepseek-v4-flash-free");
            patchBaseConfig(envFile, "AMSHA_COMPACTION_OPENCODE_BASE_URL", "https://opencode.ai/zen/v1");
            patchBaseConfig(envFile, "AMSHA_CLASSIFIER_OPENCODE_MODEL", "deepseek-v4-flash-free");
            patchBaseConfig(envFile, "AMSHA_CLASSIFIER_OPENCODE_BASE_URL", "https://opencode.ai/zen/v1");
        }
        if (ModelDefaults.PROVIDER_HUGGINGFACE.equalsIgnoreCase(provider)) {
            patchBaseConfig(envFile, "AMSHA_COMPACTION_HUGGINGFACE_MODEL", "meta-llama/Llama-3.3-70B-Instruct");
            patchBaseConfig(envFile, "AMSHA_COMPACTION_HUGGINGFACE_BASE_URL", "https://router.huggingface.co/v1");
            patchBaseConfig(envFile, "AMSHA_CLASSIFIER_HUGGINGFACE_MODEL", "meta-llama/Llama-3.3-70B-Instruct");
            patchBaseConfig(envFile, "AMSHA_CLASSIFIER_HUGGINGFACE_BASE_URL", "https://router.huggingface.co/v1");
        }
        if (ModelDefaults.PROVIDER_MISTRAL.equalsIgnoreCase(provider)) {
            patchBaseConfig(envFile, "AMSHA_COMPACTION_MISTRAL_MODEL", "codestral-latest");
            patchBaseConfig(envFile, "AMSHA_COMPACTION_MISTRAL_BASE_URL", "https://api.mistral.ai/v1");
            patchBaseConfig(envFile, "AMSHA_CLASSIFIER_MISTRAL_MODEL", "codestral-latest");
            patchBaseConfig(envFile, "AMSHA_CLASSIFIER_MISTRAL_BASE_URL", "https://api.mistral.ai/v1");
        }
        if (ModelDefaults.PROVIDER_CLOUDFLARE.equalsIgnoreCase(provider)) {
            patchBaseConfig(envFile, ConfigKeys.CLOUDFLARE_ACCOUNT_ID, "");
            patchBaseConfig(envFile, "AMSHA_COMPACTION_CLOUDFLARE_MODEL", "@cf/meta/llama-3.3-70b-instruct-fp8-fast");
            patchBaseConfig(
                    envFile,
                    "AMSHA_COMPACTION_CLOUDFLARE_BASE_URL",
                    "https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1");
            patchBaseConfig(envFile, "AMSHA_CLASSIFIER_CLOUDFLARE_MODEL", "@cf/meta/llama-3.3-70b-instruct-fp8-fast");
            patchBaseConfig(
                    envFile,
                    "AMSHA_CLASSIFIER_CLOUDFLARE_BASE_URL",
                    "https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1");
        }
        if (ModelDefaults.PROVIDER_COHERE.equalsIgnoreCase(provider)) {
            patchBaseConfig(envFile, "AMSHA_COMPACTION_COHERE_MODEL", "command-r-08-2024");
            patchBaseConfig(envFile, "AMSHA_COMPACTION_COHERE_BASE_URL", "https://api.cohere.ai/compatibility/v1");
            patchBaseConfig(envFile, "AMSHA_CLASSIFIER_COHERE_MODEL", "command-r-08-2024");
            patchBaseConfig(envFile, "AMSHA_CLASSIFIER_COHERE_BASE_URL", "https://api.cohere.ai/compatibility/v1");
        }
        if (ModelDefaults.PROVIDER_CODEX.equalsIgnoreCase(provider)) {
            patchBaseConfig(envFile, "AMSHA_COMPACTION_CODEX_MODEL", "gpt-5.6-luna");
            patchBaseConfig(envFile, "AMSHA_COMPACTION_CODEX_BASE_URL", "http://127.0.0.1:8787/v1");
            patchBaseConfig(envFile, "AMSHA_CLASSIFIER_CODEX_MODEL", "gpt-5.6-luna");
            patchBaseConfig(envFile, "AMSHA_CLASSIFIER_CODEX_BASE_URL", "http://127.0.0.1:8787/v1");
        }
    }

    private static void patchBaseConfig(Path envFile, String key, String value) {
        EnvConfigInitializer.patchEnvFile(envFile, key, value, EnvConfigInitializer.PatchMode.INIT);
        if (System.getProperty(key) == null && System.getenv(key) == null) {
            System.setProperty(key, value);
        }
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
                .filter(value -> !ModelDefaults.PROVIDER_INFINITE_LOOP.equalsIgnoreCase(value))
                .collect(Collectors.joining(","));
        return providers.isBlank() ? fallback : providers;
    }

    private static String configuredProvider() {
        String value = System.getProperty(ConfigKeys.LLM_PROVIDER);
        if (value == null) value = System.getenv(ConfigKeys.LLM_PROVIDER);
        return value == null ? ModelDefaults.DEFAULT_PROVIDER : value.trim();
    }

    private static boolean shouldPromptUnifiedKey(List<VarSpec> missing) {
        String provider = configuredProvider().toLowerCase(Locale.ROOT);
        if (provider.isBlank()) return false;
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
                || resp.trim().toLowerCase(Locale.ROOT).startsWith(YES_RESPONSE_PREFIX)) {
            String provider = configuredProvider().toLowerCase(Locale.ROOT);
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
        if (ModelDefaults.PROVIDER_CLOUDFLARE.equalsIgnoreCase(provider)) {
            String currentAcc = Optional.ofNullable(System.getProperty(ConfigKeys.CLOUDFLARE_ACCOUNT_ID))
                    .or(() -> Optional.ofNullable(System.getenv(ConfigKeys.CLOUDFLARE_ACCOUNT_ID)))
                    .orElse("");
            if (currentAcc.isBlank()) {
                String accId = readLineWithFallback(
                        System.console(), "  └─ Enter Cloudflare Account ID (dash.cloudflare.com): ");
                if (accId != null && !accId.isBlank()) {
                    EnvConfigInitializer.patchEnvFile(
                            envFile,
                            ConfigKeys.CLOUDFLARE_ACCOUNT_ID,
                            accId.trim(),
                            EnvConfigInitializer.PatchMode.UPDATE);
                    System.setProperty(ConfigKeys.CLOUDFLARE_ACCOUNT_ID, accId.trim());
                    System.err.println("     ✓ Cloudflare Account ID saved.");
                }
            }
        }
        System.err.println("     ✓ Unified key saved.");
    }

    private static String llmApiKeyForProvider(String provider) {
        return ProviderApiKeyLookup.llmApiKeyForProvider(provider);
    }

    /**
     * Returns the per-provider env-var name for the compaction slot's API key,
     * or {@code null} if the provider does not require an API key (e.g. ollama, omlx, codex).
     */
    private static String compactionApiKeyForProvider(String provider) {
        return ProviderApiKeyLookup.compactionApiKeyForProvider(provider);
    }

    /**
     * Returns the per-provider env-var name for the intent-classifier slot's API key,
     * or {@code null} if the provider does not require an API key (e.g. ollama, omlx, codex).
     */
    private static String classifierApiKeyForProvider(String provider) {
        return ProviderApiKeyLookup.classifierApiKeyForProvider(provider);
    }

    private static boolean promptTelegramEnablement(Path envFile) {
        System.err.println("  ┌─ Telegram Integration");
        String response = readLineWithFallback(System.console(), "  └─ Enable Telegram? [y/N]: ");
        boolean enabled =
                response != null && response.trim().toLowerCase(Locale.ROOT).startsWith(YES_RESPONSE_PREFIX);
        EnvConfigInitializer.patchEnvFile(
                envFile, ConfigKeys.TELEGRAM_ENABLED, String.valueOf(enabled), EnvConfigInitializer.PatchMode.UPDATE);
        System.setProperty(ConfigKeys.TELEGRAM_ENABLED, String.valueOf(enabled));
        return enabled;
    }

    private static boolean promptOptionalModule(Path envFile, String key, String label) {
        System.err.println("  ┌─ " + label);
        String response = readLineWithFallback(System.console(), "  └─ Configure " + key + "? [y/N]: ");
        boolean enabled =
                response != null && response.trim().toLowerCase(Locale.ROOT).startsWith(YES_RESPONSE_PREFIX);
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
                + (isSensitiveKey(spec.key()) ? " (input is being read; characters are hidden)" : "")
                + (isAutoGeneratableKey(spec.key()) ? " (Enter to auto-generate): " : ": ");
        String value = isSensitiveKey(spec.key())
                ? readPasswordWithFallback(System.console(), prompt)
                : readLineWithFallback(System.console(), prompt);

        if (value == null || value.isBlank()) {
            if (isAutoGeneratableKey(spec.key())) {
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
        return key.startsWith(TELEGRAM_ENV_PREFIX) || key.startsWith(AMSHA_TELEGRAM_ENV_PREFIX);
    }

    @SuppressWarnings("java:S2119")
    static boolean isAutoGeneratableKey(String key) {
        return AUTO_GENERATABLE_KEYS.contains(key);
    }

    static boolean isSensitiveKey(String key) {
        return SENSITIVE_KEYS.contains(key);
    }

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
        // No console attached (pipe, service manager, container without a TTY, test
        // harness). Only consume input that is already buffered — calling read() on an
        // idle stdin blocks forever, which would hang bootstrap with no timeout guard.
        // Treat "nothing buffered" as "skip this prompt".
        try {
            if (System.in.available() == 0) return null;
        } catch (IOException _) {
            return null;
        }
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
