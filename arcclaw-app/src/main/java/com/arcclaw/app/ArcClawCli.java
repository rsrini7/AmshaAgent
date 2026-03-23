package com.arcclaw.app;

import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.inject.Inject;

import com.arcclaw.app.cli.AttachCommand;
import com.arcclaw.app.cli.ChatCommand;
import com.arcclaw.app.util.CliUtils;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * ArcClaw Dual-Mode Entry Point.
 * <p>
 * Routes to either {@link AskCommand} (CLI one-shot) or
 * {@link ServeCommand} (daemon/REST API) via picocli subcommands.
 * <p>
 * <strong>Data-dir lifecycle:</strong> The static {@code main()} method
 * provisions {@code ARCCLAW_DATA_DIR} <em>before</em> {@code Quarkus.run()}
 * is called, ensuring the directory exists before Hibernate boots.
 * <p>
 * <strong>Config validation lifecycle:</strong> {@link CliUtils#validateRequiredEnvVars}
 * also runs before {@code Quarkus.run()} so the user sees one clear actionable
 * message instead of a Quarkus stack trace when required env vars are absent.
 * We do NOT load .env here — Quarkus owns config loading via its SmallRye
 * config system. We only check whether required vars are present.
 */
@QuarkusMain
@Command(name = "arcclaw", mixinStandardHelpOptions = true, subcommands = {
        AskCommand.class,
        ServeCommand.class,
        ChatCommand.class,
        AttachCommand.class
})
public class ArcClawCli implements Runnable, QuarkusApplication {

    @Inject
    CommandLine.IFactory factory;

    // -------------------------------------------------------------------------
    // Static bootstrap — runs BEFORE Quarkus CDI container starts
    // -------------------------------------------------------------------------

    public static void main(String... args) {

        // 1. Validate required env vars early — before Quarkus boots.
        //    Shows a clear setup guide if anything is missing, then exits.
        //    Does NOT load .env — Quarkus handles config loading itself.
        CliUtils.validateRequiredEnvVars(args);

        // 2. Provision data directory before Hibernate starts.
        try {
            String dataDir = System.getProperty("ARCCLAW_DATA_DIR",
                    System.getenv().getOrDefault("ARCCLAW_DATA_DIR", "./data"));
            Path dirPath = Path.of(dataDir);
            Files.createDirectories(dirPath);
            Files.createDirectories(dirPath.resolve("workspaces"));
        } catch (Exception e) {
            System.err.println("[ArcClaw] ❌ FATAL: Failed to create data directory.");
            System.err.println("[ArcClaw]    Path: " + System.getProperty("ARCCLAW_DATA_DIR",
                    System.getenv().getOrDefault("ARCCLAW_DATA_DIR", "./data")));
            System.err.println("[ArcClaw]    Cause: " + e.getMessage());
            System.err.println("[ArcClaw]    Fix: ensure the directory is writable or set ARCCLAW_DATA_DIR.");
            System.exit(1);
        }

        // 3. Boot Quarkus — config system, CDI, and all extensions start here.
        Quarkus.run(ArcClawCli.class, args);
    }

    // -------------------------------------------------------------------------
    // QuarkusApplication — runs AFTER CDI container is ready
    // -------------------------------------------------------------------------

    @Override
    public int run(String... args) {
        int exitCode = new CommandLine(this, factory).execute(args);
        return exitCode;
    }

    // -------------------------------------------------------------------------
    // Default action (no subcommand) — print help
    // -------------------------------------------------------------------------

    @Override
    public void run() {
        new CommandLine(this, factory).usage(System.out);
    }

}
