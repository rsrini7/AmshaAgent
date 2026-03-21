package com.arcclaw.app.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Pre-boot CLI utilities for ArcClaw.
 *
 * <p><strong>Design contract:</strong> These methods run <em>before</em>
 * {@code Quarkus.run()} — the CDI container and config system have not
 * started yet. We deliberately do NOT call {@code System.setProperty()} or
 * load values into the JVM environment. Quarkus owns config loading. Our only
 * job here is to fail fast with a clear human-readable message when required
 * configuration is absent, so users never see a cryptic Quarkus stack trace.
 *
 * <p><strong>Telegram validation strategy:</strong>
 * <ul>
 *   <li>{@code attach} mode — hard error if any Telegram var is missing.
 *       The command has no purpose without Telegram.</li>
 *   <li>{@code serve} mode — Telegram is optional. We soft-warn (print to
 *       stderr, do not exit) in three cases:
 *       <ol>
 *         <li>{@code ARCCLAW_TELEGRAM_ENABLED=true} but vars are missing
 *             → Telegram will fail at runtime.</li>
 *         <li>{@code TELEGRAM_BOT_TOKEN} is set but
 *             {@code ARCCLAW_TELEGRAM_ENABLED} is not true
 *             → Looks like an oversight; bot won't start.</li>
 *         <li>{@code TELEGRAM_BOT_TOKEN} set but access-control vars missing
 *             → Security hole: bot would be open to anyone.</li>
 *       </ol>
 *   </li>
 * </ul>
 */
public class CliUtils {

    // -------------------------------------------------------------------------
    // Required vars per mode
    // -------------------------------------------------------------------------

    /** Required for every mode except bare --help / --version. */
    private static final VarSpec[] CORE_VARS = {
        new VarSpec(
            "GROQ_API_KEY",
            "Groq Cloud API key (used as the LLM backend).",
            "1. Sign up at https://console.groq.com",
            "2. Create an API key — it starts with 'gsk_'",
            "3. Add:  GROQ_API_KEY=gsk_your_key_here"
        ),
        new VarSpec(
            "ARCCLAW_VAULT_PEPPER",
            "Secret pepper for the credential vault (>=32 random characters).",
            "Generate one with:",
            "  Linux/macOS:  openssl rand -base64 32",
            "  Windows:      [Convert]::ToBase64String((1..32 | %{[byte](Get-Random -Max 256)}))",
            "Then add:  ARCCLAW_VAULT_PEPPER=<generated value>",
            "WARNING: Never change this after first boot - it decrypts your stored secrets."
        ),
        new VarSpec(
            "ARCCLAW_AUDIT_HMAC_KEY",
            "HMAC key for the audit log integrity chain (>=32 random characters).",
            "Generate one with:",
            "  Linux/macOS:  openssl rand -base64 32",
            "  Windows:      [Convert]::ToBase64String((1..32 | %{[byte](Get-Random -Max 256)}))",
            "Then add:  ARCCLAW_AUDIT_HMAC_KEY=<generated value>",
            "WARNING: Never change this after first boot - it signs your audit trail."
        ),
        new VarSpec(
            "ARCCLAW_API_KEY_INTERNAL",
            "Bearer token for ArcClaw's REST API (used in Authorization: Bearer <token> header).",
            "Generate a strong random key with:",
            "  Linux/macOS:  openssl rand -base64 32",
            "  Windows:      [Convert]::ToBase64String((1..32 | %{[byte](Get-Random -Max 256)}))",
            "Then add:  ARCCLAW_API_KEY_INTERNAL=<generated value>"
        ),
    };

    /** Required for attach mode (hard) and validated for serve mode (soft). */
    private static final VarSpec[] TELEGRAM_VARS = {
        new VarSpec(
            "TELEGRAM_BOT_TOKEN",
            "Telegram bot token from @BotFather.",
            "1. Open Telegram and message @BotFather",
            "2. Send /newbot and follow the prompts",
            "3. Copy the token and add:  TELEGRAM_BOT_TOKEN=123456:ABCdef..."
        ),
        new VarSpec(
            "ARCCLAW_TELEGRAM_ALLOWED_USER_IDS",
            "Comma-separated numeric Telegram user IDs allowed to use the bot.",
            "Find your user ID by messaging @userinfobot on Telegram.",
            "Add:  ARCCLAW_TELEGRAM_ALLOWED_USER_IDS=123456789"
        ),
        new VarSpec(
            "ARCCLAW_TELEGRAM_ALLOWED_USERS",
            "Comma-separated Telegram usernames (without @) allowed to use the bot.",
            "Add:  ARCCLAW_TELEGRAM_ALLOWED_USERS=yourusername"
        ),
    };

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Validates required environment variables before Quarkus boots.
     *
     * <p>Peeks at {@code args[0]} to determine which vars are required.
     * For core vars: exits with code 1 and a full setup guide if any are absent.
     * For Telegram vars in serve mode: prints warnings but does not exit.
     * Does NOT load or set any properties — Quarkus owns config.
     *
     * @param args the raw CLI args passed to {@code main()}
     */
    public static void validateRequiredEnvVars(String[] args) {
        String mode = args.length > 0 ? args[0].toLowerCase() : "";

        // No validation needed for help/version flags or bare invocation
        if (mode.equals("--help") || mode.equals("-h")
                || mode.equals("--version") || mode.equals("-v")
                || mode.isEmpty()) {
            return;
        }

        boolean dotEnvPresent = Files.exists(Path.of(".env"));
        List<VarSpec> missing = new ArrayList<>();

        // ── Core vars — always required ───────────────────────────────────────
        for (VarSpec spec : CORE_VARS) {
            if (!isSet(spec.key)) missing.add(spec);
        }

        // ── Telegram vars — hard required for attach, soft-warn for serve ─────
        if (mode.equals("attach")) {
            for (VarSpec spec : TELEGRAM_VARS) {
                if (!isSet(spec.key)) missing.add(spec);
            }
        } else if (mode.equals("serve")) {
            warnTelegramIfPartial();
        }

        if (missing.isEmpty()) return;

        // ── Print setup guide and exit ────────────────────────────────────────
        System.err.println();
        System.err.println("  X  ArcClaw cannot start - required configuration is missing.");
        System.err.println();

        if (!dotEnvPresent) {
            System.err.println("  INFO: No .env file found in: " + Path.of(".env").toAbsolutePath());
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
            System.err.println("  KEY: " + spec.key);
            System.err.println("       " + spec.description);
            for (String step : spec.howToGet) {
                System.err.println("       " + step);
            }
        }

        System.err.println();
        System.err.println("  " + "-".repeat(74));
        System.err.println("  After updating .env, re-run:  ./run.sh " + mode
                + "  (or ./run.ps1 " + mode + " on Windows)");
        System.err.println();
        System.exit(1);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Soft-warns about partial or inconsistent Telegram configuration in
     * serve mode. Prints to stderr but does NOT exit — Telegram is optional
     * for serve. Three cases are detected:
     * <ol>
     *   <li>ARCCLAW_TELEGRAM_ENABLED=true but some Telegram vars are missing.</li>
     *   <li>TELEGRAM_BOT_TOKEN is set but ARCCLAW_TELEGRAM_ENABLED is not true
     *       (token present but Telegram won't actually start).</li>
     *   <li>TELEGRAM_BOT_TOKEN set but access-control vars are missing
     *       (security hole: bot would accept messages from anyone).</li>
     * </ol>
     */
    private static void warnTelegramIfPartial() {
        boolean enabled    = "true".equalsIgnoreCase(System.getenv("ARCCLAW_TELEGRAM_ENABLED"));
        boolean hasToken   = isSet("TELEGRAM_BOT_TOKEN");
        boolean hasUserIds = isSet("ARCCLAW_TELEGRAM_ALLOWED_USER_IDS");
        boolean hasUsers   = isSet("ARCCLAW_TELEGRAM_ALLOWED_USERS");

        // Case 1: explicitly enabled but vars missing
        if (enabled) {
            List<String> missingKeys = new ArrayList<>();
            if (!hasToken)   missingKeys.add("TELEGRAM_BOT_TOKEN");
            if (!hasUserIds) missingKeys.add("ARCCLAW_TELEGRAM_ALLOWED_USER_IDS");
            if (!hasUsers)   missingKeys.add("ARCCLAW_TELEGRAM_ALLOWED_USERS");
            if (!missingKeys.isEmpty()) {
                System.err.println();
                System.err.println("  WARN: ARCCLAW_TELEGRAM_ENABLED=true but Telegram vars are missing:");
                missingKeys.forEach(k -> System.err.println("        - " + k));
                System.err.println("        Telegram will fail at runtime. Add the missing vars to .env");
                System.err.println("        or run in attach mode:  ./run.sh attach");
                System.err.println();
            }
            return;
        }

        // Case 2: token present but Telegram not enabled — looks like an oversight
        if (hasToken && !enabled) {
            System.err.println();
            System.err.println("  WARN: TELEGRAM_BOT_TOKEN is set but ARCCLAW_TELEGRAM_ENABLED is not 'true'.");
            System.err.println("        Telegram will NOT start in serve mode.");
            System.err.println("        To enable: add  ARCCLAW_TELEGRAM_ENABLED=true  to your .env");
            System.err.println("        To run as Telegram daemon: ./run.sh attach");
            System.err.println();
        }

        // Case 3: token set and enabled, but access control missing — security hole
        if (hasToken && enabled && (!hasUserIds || !hasUsers)) {
            System.err.println();
            System.err.println("  WARN: TELEGRAM_BOT_TOKEN is set but access-control vars are missing.");
            if (!hasUserIds) System.err.println("        - ARCCLAW_TELEGRAM_ALLOWED_USER_IDS is not set");
            if (!hasUsers)   System.err.println("        - ARCCLAW_TELEGRAM_ALLOWED_USERS is not set");
            System.err.println("        Without these, the bot may respond to messages from anyone.");
            System.err.println("        Find your user ID by messaging @userinfobot on Telegram.");
            System.err.println();
        }
    }

    /** True if the key is non-null, non-blank, and not a placeholder. */
    private static boolean isSet(String key) {
        String val = System.getenv(key);
        if (val == null || val.isBlank()) return false;
        return !val.contains("changeme") && !val.contains("<") && !val.equals("your_key_here");
    }

    // -------------------------------------------------------------------------
    // Data
    // -------------------------------------------------------------------------

    private static class VarSpec {
        final String key;
        final String description;
        final String[] howToGet;

        VarSpec(String key, String description, String... howToGet) {
            this.key = key;
            this.description = description;
            this.howToGet = howToGet;
        }
    }
}