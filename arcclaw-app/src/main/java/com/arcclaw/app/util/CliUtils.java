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
            "Secret pepper for the credential vault (≥32 random characters).",
            "Generate one with:",
            "  Linux/macOS:  openssl rand -base64 32",
            "  Windows:      [Convert]::ToBase64String((1..32 | %{[byte](Get-Random -Max 256)}))",
            "⚠️  Never change this after first boot — it decrypts your stored secrets."
        ),
        new VarSpec(
            "ARCCLAW_AUDIT_HMAC_KEY",
            "HMAC key for the audit log integrity chain (≥32 random characters).",
            "Generate one with:",
            "  Linux/macOS:  openssl rand -base64 32",
            "  Windows:      [Convert]::ToBase64String((1..32 | %{[byte](Get-Random -Max 256)}))",
            "⚠️  Never change this after first boot — it signs your audit trail."
        ),
        new VarSpec(
            "ARCCLAW_API_KEY_INTERNAL",
            "Bearer token for ArcClaw's REST API.",
            "Set any secret string, e.g.:",
            "  ARCCLAW_API_KEY_INTERNAL=my-secret-api-key-changeme"
        ),
    };

    /** Additional vars required only for 'attach' (Telegram daemon) mode. */
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
     * Exits with code 1 and a full setup guide if any required var is absent.
     * Does NOT load or set any properties — Quarkus owns config.
     *
     * @param args the raw CLI args passed to {@code main()}
     */
    public static void validateRequiredEnvVars(String[] args) {
        String mode = args.length > 0 ? args[0].toLowerCase() : "";

        // No validation needed for help/version flags
        if (mode.equals("--help") || mode.equals("-h")
                || mode.equals("--version") || mode.equals("-v")
                || mode.isEmpty()) {
            return;
        }

        boolean dotEnvPresent = Files.exists(Path.of(".env"));
        List<VarSpec> missing = new ArrayList<>();

        // Always check core vars
        for (VarSpec spec : CORE_VARS) {
            if (!isSet(spec.key)) missing.add(spec);
        }

        // Telegram vars only for attach mode
        if (mode.equals("attach")) {
            for (VarSpec spec : TELEGRAM_VARS) {
                if (!isSet(spec.key)) missing.add(spec);
            }
        }

        if (missing.isEmpty()) return;

        // ── Print the setup guide ─────────────────────────────────────────────
        System.err.println();
        System.err.println("  ❌  ArcClaw cannot start — required configuration is missing.");
        System.err.println();

        if (!dotEnvPresent) {
            System.err.println("  ℹ️   No .env file found in: " + Path.of(".env").toAbsolutePath());
            System.err.println("      Create one by copying the example:");
            System.err.println("        cp .env.example .env          (Linux/macOS)");
            System.err.println("        Copy-Item .env.example .env   (Windows)");
            System.err.println("      Then fill in the values listed below.");
        } else {
            System.err.println("  ℹ️   .env found at: " + Path.of(".env").toAbsolutePath());
            System.err.println("      The following keys are missing or empty — check for typos.");
        }

        System.err.println();
        System.err.println("  ── Missing variables " + "─".repeat(55));

        for (VarSpec spec : missing) {
            System.err.println();
            System.err.println("  🔑  " + spec.key);
            System.err.println("      " + spec.description);
            for (String step : spec.howToGet) {
                System.err.println("      " + step);
            }
        }

        System.err.println();
        System.err.println("  " + "─".repeat(74));
        System.err.println("  After updating .env, re-run:  ./run.sh " + mode
                + "  (or ./run.ps1 " + mode + " on Windows)");
        System.err.println();
        System.exit(1);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /** True if the key is non-null, non-blank, and not a placeholder. */
    private static boolean isSet(String key) {
        String val = System.getenv(key);
        if (val == null || val.isBlank()) return false;
        // Treat common placeholder values as unset
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