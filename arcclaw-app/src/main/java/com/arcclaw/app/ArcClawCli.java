package com.arcclaw.app;

import java.nio.file.Files;
import java.nio.file.Path;

import com.arcclaw.app.cli.AttachCommand;
import com.arcclaw.app.cli.ChatCommand;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
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

        // Load .env manually — Quarkus config system hasn't started yet
        // CliUtils.loadDotEnv();
        // CliUtils.validateRequiredEnvVars(); 

         try {
            String dataDir = System.getProperty("ARCCLAW_DATA_DIR",
                    System.getenv().getOrDefault("ARCCLAW_DATA_DIR", "./data"));
            Path dirPath = Path.of(dataDir);
            System.out.printf("[ArcClaw] Data dir: %s%n", dirPath.toAbsolutePath());
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
