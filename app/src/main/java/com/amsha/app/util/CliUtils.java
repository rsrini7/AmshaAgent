package com.amsha.app.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.amsha.common.config.ConfigKeys;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.security.api.VaultConstants;

/**
 * Pre-boot CLI utilities for Amsha Agent.
 *
 * <p><strong>Design contract:</strong> These methods run <em>before</em>
 * {@code Quarkus.run()} - the CDI container and config system have not
 * started yet. We deliberately do NOT call {@code System.setProperty()} or
 * load values into the JVM environment. Quarkus owns config loading. Our only
 * job here is to fail fast with a clear human-readable message when required
 * configuration is absent, so users never see a cryptic Quarkus stack trace.
 *
 * <p><strong>Telegram validation strategy:</strong>
 * <ul>
 *   <li>{@code attach} mode - hard error if any Telegram var is missing.</li>
 *   <li>{@code serve} mode - Telegram is optional. We soft-warn (print to
 *       stderr, do not exit) in three cases:
 *       <ol>
 *         <li>{@code AMSHA_TELEGRAM_ENABLED=true} but vars are missing.</li>
 *         <li>{@code TELEGRAM_BOT_TOKEN} set but ENABLED not true.</li>
 *         <li>{@code TELEGRAM_BOT_TOKEN} set but access-control vars missing.</li>
 *       </ol>
 *   </li>
 * </ul>
 */
public class CliUtils {

    // -------------------------------------------------------------------------
    // Required vars per mode
    // -------------------------------------------------------------------------

    /**
     * Required for every mode except bare --help / --version.
     * Per-provider API keys (AMSHA_GROQ_API_KEY etc.) are provider-conditional
     * and resolved via {@link ProviderKeySpecs}.
     * Compaction, intent-classifier, and embedding API keys are computed dynamically
     * from the selected provider in {@link #getMissingVarSpecs(String[])}.
     */
    private static final VarSpec[] CORE_VARS = {
        new VarSpec(
                VaultConstants.VAULT_PEPPER,
                "Secret pepper for the credential vault (>=32 random characters).",
                "Generate one with:",
                "  Linux/macOS:  openssl rand -base64 32",
                "  Windows:      [Convert]::ToBase64String((1..32 | %{[byte](Get-Random -Max 256)}))",
                "Then add:  AMSHA_VAULT_PEPPER=<generated value>",
                "WARNING: Never change this after first boot - it decrypts your stored secrets."),
        new VarSpec(
                VaultConstants.AUDIT_HMAC_KEY,
                "HMAC key for the audit log integrity chain (>=32 random characters).",
                "Generate one with:",
                "  Linux/macOS:  openssl rand -base64 32",
                "  Windows:      [Convert]::ToBase64String((1..32 | %{[byte](Get-Random -Max 256)}))",
                "Then add:  AMSHA_AUDIT_HMAC_KEY=<generated value>",
                "WARNING: Never change this after first boot - it signs your audit trail."),
        new VarSpec(
                VaultConstants.API_KEYS_INTERNAL,
                "Bearer token for Amsha Agent's REST API (used in Authorization: Bearer <token> header).",
                "Generate a strong random key with:",
                "  Linux/macOS:  openssl rand -base64 32",
                "  Windows:      [Convert]::ToBase64String((1..32 | %{[byte](Get-Random -Max 256)}))",
                "Then add:  AMSHA_API_KEYS_INTERNAL=<generated value>"),
        new VarSpec(
                VaultConstants.API_KEYS_CI,
                "CI/CD API key for pipeline smoke tests (read-only).",
                "Generate a strong random key with:",
                "  Linux/macOS:  openssl rand -base64 32",
                "  Windows:      [Convert]::ToBase64String((1..32 | %{[byte](Get-Random -Max 256)}))",
                "Then add:  AMSHA_API_KEYS_CI=<generated value>"),
        new VarSpec(
                VaultConstants.AUTH_HMAC_KEY,
                "HMAC key for user session and invitation token integrity (>=32 random characters).",
                "Generate one with:",
                "  Linux/macOS:  openssl rand -base64 32",
                "  Windows:      [Convert]::ToBase64String((1..32 | %{[byte](Get-Random -Max 256)}))",
                "Then add:  AMSHA_AUTH_HMAC_KEY=<generated value>",
                "WARNING: Never change this after first boot - it signs your session and invitation tokens."),
    };

    /** Required for attach mode (hard) and validated for serve mode (soft). */
    private static final VarSpec[] TELEGRAM_VARS = {
        new VarSpec(
                ConfigKeys.TELEGRAM_BOT_TOKEN,
                "Telegram bot token from @BotFather.",
                "1. Open Telegram and message @BotFather",
                "2. Send /newbot and follow the prompts",
                "3. Copy the token and add:  TELEGRAM_BOT_TOKEN=123456:ABCdef..."),
        new VarSpec(
                "AMSHA_TELEGRAM_ALLOWED_USER_IDS",
                "Comma-separated numeric Telegram user IDs allowed to use the bot.",
                "Find your user ID by messaging @userinfobot on Telegram.",
                "Add:  AMSHA_TELEGRAM_ALLOWED_USER_IDS=123456789"),
        new VarSpec(
                "AMSHA_TELEGRAM_ALLOWED_USERS",
                "Comma-separated Telegram usernames (without @) allowed to use the bot.",
                "Add:  AMSHA_TELEGRAM_ALLOWED_USERS=yourusername"),
    };

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns missing required VarSpecs for the given mode.
     * Called by BinaryBootstrap for interactive first-run setup.
     */
    public static List<VarSpec> getMissingVarSpecs(String[] args) {
        if (args != null && args.length > 0 && "models".equalsIgnoreCase(args[0])) {
            return List.of();
        }

        List<VarSpec> missing = new ArrayList<>();

        // 1. LLM Primary Key first
        String llmProvider = Optional.ofNullable(System.getenv(ConfigKeys.LLM_PROVIDER))
                .orElseGet(() -> System.getProperty(ConfigKeys.LLM_PROVIDER, ModelDefaults.PROVIDER_GROQ))
                .toLowerCase(Locale.ROOT);
        VarSpec providerKeySpec = ProviderKeySpecs.llmKeySpecFor(llmProvider);
        if (providerKeySpec != null && !isSet(providerKeySpec.key())) missing.add(providerKeySpec);

        // 1b. Per-slot API keys for compaction / intent-classifier / embedding.
        // Each infinite-loop provider list selects the first entry for key resolution.
        String compactionProvider = ProviderKeySpecs.firstProvider(
                Optional.ofNullable(System.getenv(ConfigKeys.AMSHA_INFINITE_LOOP_COMPACTION_PROVIDERS))
                        .orElseGet(() -> System.getProperty(
                                ConfigKeys.AMSHA_INFINITE_LOOP_COMPACTION_PROVIDERS, "groq,ollama")));
        String classifierProvider = ProviderKeySpecs.firstProvider(
                Optional.ofNullable(System.getenv(ConfigKeys.AMSHA_INFINITE_LOOP_CLASSIFIER_PROVIDERS))
                        .orElseGet(() -> System.getProperty(
                                ConfigKeys.AMSHA_INFINITE_LOOP_CLASSIFIER_PROVIDERS, "groq,ollama")));
        String embeddingProvider = ProviderKeySpecs.firstProvider(
                Optional.ofNullable(System.getenv(ConfigKeys.AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS))
                        .orElseGet(() -> System.getProperty(
                                ConfigKeys.AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS, ModelDefaults.PROVIDER_OLLAMA)));

        VarSpec compactionKeySpec = ProviderKeySpecs.compactionKeySpecFor(compactionProvider);
        if (compactionKeySpec != null && !isSet(compactionKeySpec.key())) missing.add(compactionKeySpec);
        VarSpec classifierKeySpec = ProviderKeySpecs.classifierKeySpecFor(classifierProvider);
        if (classifierKeySpec != null && !isSet(classifierKeySpec.key())) missing.add(classifierKeySpec);
        VarSpec embeddingKeySpec = ProviderKeySpecs.embeddingKeySpecFor(embeddingProvider);
        if (embeddingKeySpec != null && !isSet(embeddingKeySpec.key())) missing.add(embeddingKeySpec);

        // 2. Security keys
        for (VarSpec spec : CORE_VARS) {
            if (!isSet(spec.key())) missing.add(spec);
        }

        boolean attachMode = args != null && args.length > 0 && "attach".equalsIgnoreCase(args[0]);
        if (attachMode || !isExplicitOptOut("AMSHA_TELEGRAM_ENABLED")) {
            for (VarSpec spec : TELEGRAM_VARS) {
                if (!isSet(spec.key())) missing.add(spec);
            }
        }

        if (!isSet(VaultConstants.TOOLS_GITHUB_TOKEN) && !isExplicitOptOut(VaultConstants.TOOLS_GITHUB_TOKEN)) {
            missing.add(new VarSpec(
                    VaultConstants.TOOLS_GITHUB_TOKEN,
                    "Optional GitHub Personal Access Token (classic or fine-grained).",
                    "Recommended for searching and managing code in GitHub repositories.",
                    "1. Go to https://github.com/settings/tokens",
                    "2. Create a token with 'repo' and 'workflow' scopes (or relevant fine-grained scopes)",
                    "3. Add:  AMSHA_TOOLS_GITHUB_TOKEN=ghp_your_key_here"));
        }

        if (!isSet(VaultConstants.TOOLS_SERPER_API_KEY) && !isExplicitOptOut(VaultConstants.TOOLS_SERPER_API_KEY)) {
            missing.add(new VarSpec(
                    VaultConstants.TOOLS_SERPER_API_KEY,
                    "Optional Serper.dev API key for Google Search integration.",
                    "1. Sign up at https://serper.dev - 2,500 free searches",
                    "2. Copy your API key",
                    "3. Add:  AMSHA_TOOLS_SERPER_API_KEY=your_key_here"));
        }

        if (!isSet(VaultConstants.TOOLS_BRAVE_API_KEY) && !isExplicitOptOut(VaultConstants.TOOLS_BRAVE_API_KEY)) {
            missing.add(new VarSpec(
                    VaultConstants.TOOLS_BRAVE_API_KEY,
                    "Optional Brave Search API key.",
                    "1. Sign up at https://api.search.brave.com",
                    "2. Create an API key",
                    "3. Add:  AMSHA_TOOLS_BRAVE_API_KEY=your_key_here"));
        }

        return missing;
    }

    private static boolean isExplicitOptOut(String key) {
        String val = System.getProperty(key, System.getenv(key));
        return val != null
                && (val.equalsIgnoreCase("none") || val.equalsIgnoreCase("false") || val.equalsIgnoreCase("skip"));
    }

    /**
     * Validates required environment variables before Quarkus boots.
     *
     * <p>Peeks at {@code args[0]} to determine which vars are required.
     * For core vars: exits with code 1 and a full setup guide if any are absent.
     * For Telegram vars in serve mode: prints warnings but does not exit.
     * Does NOT load or set any properties - Quarkus owns config.
     *
     * @param args the raw CLI args passed to {@code main()}
     */
    public static void validateRequiredEnvVars(String[] args) {
        String mode = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";

        // No validation needed for help/version flags or models management command
        if (mode.equals("--help")
                || mode.equals("-h")
                || mode.equals("--version")
                || mode.equals("-v")
                || mode.equals("models")) {
            return;
        }

        boolean dotEnvPresent = Files.exists(Path.of(".env"));
        List<VarSpec> missing = new ArrayList<>();

        for (VarSpec spec : CORE_VARS) {
            if (!isSet(spec.key())) missing.add(spec);
        }

        String llmProvider = Optional.ofNullable(System.getenv(ConfigKeys.LLM_PROVIDER))
                .map(String::toLowerCase)
                .orElse(ModelDefaults.PROVIDER_GROQ);
        VarSpec providerKeySpec = ProviderKeySpecs.llmKeySpecFor(llmProvider);
        if (providerKeySpec != null && !isSet(providerKeySpec.key())) {
            missing.add(providerKeySpec);
        }

        if (mode.equals("attach")) {
            for (VarSpec spec : TELEGRAM_VARS) {
                if (!isSet(spec.key())) missing.add(spec);
            }
        } else if (mode.equals("serve")) {
            warnTelegramIfPartial();
        }

        if (missing.isEmpty()) return;

        printSetupGuide(missing, dotEnvPresent, mode);
        System.exit(1);
    }

    private static void printSetupGuide(List<VarSpec> missing, boolean dotEnvPresent, String mode) {
        System.err.println();
        System.err.println("  X  Amsha Agent cannot start - required configuration is missing.");
        System.err.println();

        if (!dotEnvPresent) {
            System.err.println(
                    "  INFO: No .env file found in: " + Path.of(".env").toAbsolutePath());
            System.err.println("        Create one by copying the example:");
            System.err.println("          cp .env.example .env          (Linux/macOS)");
            System.err.println("          Copy-Item .env.example .env   (Windows)");
            System.err.println("        Then fill in the values listed below.");
        } else {
            System.err.println("  INFO: .env found at: " + Path.of(".env").toAbsolutePath());
            System.err.println("        The following keys are missing or empty - check for typos.");
        }

        System.err.println();
        System.err.println("  -- Missing variables " + "-".repeat(55));

        for (VarSpec spec : missing) {
            System.err.println();
            System.err.println("  KEY: " + spec.key());
            System.err.println("       " + spec.description());
            for (String step : spec.howToGet()) {
                System.err.println("       " + step);
            }
        }

        System.err.println();
        System.err.println("  " + "-".repeat(75));
        System.err.println("  After setting all values, re-run your command.");
        System.err.println("    ./amsha " + mode + "    (Linux/macOS/WSL2)");
        System.err.println("    .\\amsha.exe " + mode + "   (Windows)");
        System.err.println();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Soft-warns about partial or inconsistent Telegram configuration in
     * serve mode. Prints to stderr but does NOT exit - Telegram is optional
     * for serve. Three cases are detected:
     * <ol>
     *   <li>AMSHA_TELEGRAM_ENABLED=true but some Telegram vars are missing.</li>
     *   <li>TELEGRAM_BOT_TOKEN is set but AMSHA_TELEGRAM_ENABLED is not true
     *       (token present but Telegram won't actually start).</li>
     *   <li>TELEGRAM_BOT_TOKEN set but access-control vars are missing
     *       (security hole: bot would accept messages from anyone).</li>
     * </ol>
     */
    private static void warnTelegramIfPartial() {
        String val = System.getProperty("AMSHA_TELEGRAM_ENABLED", System.getenv("AMSHA_TELEGRAM_ENABLED"));
        boolean enabled = "true".equalsIgnoreCase(val);
        boolean hasToken = isSet(ConfigKeys.TELEGRAM_BOT_TOKEN);
        boolean hasUserIds = isSet("AMSHA_TELEGRAM_ALLOWED_USER_IDS");
        boolean hasUsers = isSet("AMSHA_TELEGRAM_ALLOWED_USERS");

        // Case 0: nothing Telegram-related set at all - gentle info nudge
        if (!enabled && !hasToken && !hasUserIds && !hasUsers) {
            System.err.println();
            System.err.println("  INFO: Telegram channel is not configured (optional).");
            System.err.println(
                    "        To enable: set these in your .env and run ./amsha attach (or amsha.exe attach):");
            System.err.println("          AMSHA_TELEGRAM_ENABLED=true");
            System.err.println("          TELEGRAM_BOT_TOKEN=<token from @BotFather>");
            System.err.println("          AMSHA_TELEGRAM_ALLOWED_USER_IDS=<your numeric Telegram user ID>");
            System.err.println("          AMSHA_TELEGRAM_ALLOWED_USERS=<your @username without @>");
            System.err.println("        Continuing in HTTP/MCP-only mode.");
            System.err.println();
            return;
        }

        // Case 1: explicitly enabled
        if (enabled) {
            List<String> missingKeys = new ArrayList<>();
            if (!hasToken) missingKeys.add(ConfigKeys.TELEGRAM_BOT_TOKEN);
            if (!hasUserIds) missingKeys.add("AMSHA_TELEGRAM_ALLOWED_USER_IDS");
            if (!hasUsers) missingKeys.add("AMSHA_TELEGRAM_ALLOWED_USERS");
            if (!missingKeys.isEmpty()) {
                System.err.println();
                System.err.println("  WARN: AMSHA_TELEGRAM_ENABLED=true but Telegram vars are missing:");
                missingKeys.forEach(k -> System.err.println("        - " + k));
                System.err.println("        Telegram will fail at runtime. Add the missing vars to .env");
                System.err.println("        or run in attach mode:  ./amsha attach");
                System.err.println();
            }

            // Case 3: token set and enabled, but access control missing - security hole
            if (hasToken && (!hasUserIds || !hasUsers)) {
                System.err.println();
                System.err.println("  WARN: TELEGRAM_BOT_TOKEN is set but access-control vars are missing.");
                if (!hasUserIds) System.err.println("        - AMSHA_TELEGRAM_ALLOWED_USER_IDS is not set");
                if (!hasUsers) System.err.println("        - AMSHA_TELEGRAM_ALLOWED_USERS is not set");
                System.err.println("        Without these, the bot may respond to messages from anyone.");
                System.err.println("        Find your user ID by messaging @userinfobot on Telegram.");
                System.err.println();
            }
            return;
        }

        // Case 2: token present but Telegram not enabled - looks like an oversight
        if (hasToken) {
            System.err.println();
            System.err.println("  WARN: TELEGRAM_BOT_TOKEN is set but AMSHA_TELEGRAM_ENABLED is not 'true'.");
            System.err.println("        Telegram will NOT start in serve mode.");
            System.err.println("        To enable: add  AMSHA_TELEGRAM_ENABLED=true  to your .env");
            System.err.println("        To run as Telegram daemon: ./amsha attach");
            System.err.println();
        }
    }

    /**
     * True if the key is non-null, non-blank, and not a known placeholder pattern.
     *
     * <p>Placeholder patterns detected:
     * <ul>
     *   <li>{@code changeme} - default dev values</li>
     *   <li>{@code <...>} - angle-bracket templates (e.g. {@code key-<random>})</li>
     *   <li>{@code your_key_here} / {@code your_..._here} - human-readable stubs</li>
     *   <li>Repeated {@code x} characters (>=8) - e.g. {@code jina_xxxxxxxx...}</li>
     * </ul>
     */
    private static boolean isSet(String key) {
        String val = System.getProperty(key, System.getenv(key));
        if (val == null || val.isBlank()) return false;
        if (val.contains("changeme")) return false;
        if (val.contains("<")) return false;
        if (val.contains("your_") && val.contains("_here")) return false;
        // Catch repeated-x placeholders like jina_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
        if (val.equalsIgnoreCase("none")) return false;
        if (val.matches(".*x{8,}.*")) return false;
        return true;
    }
}
