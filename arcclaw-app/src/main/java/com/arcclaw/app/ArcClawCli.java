package com.arcclaw.app;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import jakarta.inject.Inject;

import com.arcclaw.app.cli.AttachCommand;
import com.arcclaw.app.cli.ChatCommand;
import com.arcclaw.app.config.AppBootstrap;
import com.arcclaw.app.util.CliUtils;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * ArcClaw Dual-Mode Entry Point.
 * <p>
 * Routes to either {@link AskCommand} (CLI one-shot) or
 * {@link ServeCommand} (daemon/REST API) via picocli subcommands.
 * <p>
 * <strong>Data-dir lifecycle:</strong> The static {@code main()} method
 * provisions {@code ARCCLAW_DATA_DIR} <em>before</em> {@code Quarkus.run()}
 * is called, ensuring the directory exists before Quarkus boots.
 * The raw value is resolved to an absolute, normalised path and written
 * back into {@code System.setProperty} so that every
 * {@code ${ARCCLAW_DATA_DIR:./data}} expression in {@code application.yaml}
 * (datasource URL, arcclaw.data-dir, workspace root, etc.) resolves to the
 * same absolute location regardless of the JVM launch directory.
 * <p>
 * <strong>Config validation lifecycle:</strong> {@link CliUtils#validateRequiredEnvVars}
 * also runs before {@code Quarkus.run()} so the user sees one clear actionable
 * message instead of a Quarkus stack trace when required env vars are absent.
 * We do NOT load .env here - Quarkus owns config loading via its SmallRye
 * config system. We only check whether required vars are present.
 */
@QuarkusMain
@Command(
        name = "arcclaw",
        mixinStandardHelpOptions = true,
        subcommands = {AskCommand.class, ServeCommand.class, ChatCommand.class, AttachCommand.class})
@SuppressWarnings("java:S106")
public class ArcClawCli implements Runnable, QuarkusApplication {

    @Inject
    CommandLine.IFactory factory;

    private static volatile int exitStatus = 0;

    private static void setExitStatus(int code) {
        exitStatus = code;
    }

    public static void main(String... args) {

        // 1. Resolve binary location, load .env, and provision data/lib dirs.
        //    Must run first so that validation and Quarkus pick up correct paths.
        AppBootstrap.bootstrap(args);

        // 2. Validate required env vars early - before Quarkus boots.
        CliUtils.validateRequiredEnvVars(args);

        // 3. If no command provided, show USAGE and exit (fast-path, no Quarkus boot needed)
        if (args.length == 0) {
            printUsageGuide();
            return;
        }

        // 4. Boot Quarkus - config system, CDI, and all extensions start here.
        Quarkus.run(ArcClawCli.class, args);
        System.exit(exitStatus);
    }

    private static void printUsageGuide() {
        // ASCII Banner (from arcclaw-banner.txt)
        System.out.println("    ___                ________  ");
        System.out.println("   /   |  __________  / ____/ /___ __      __");
        System.out.println("  / /| | / ___/ ___/ / /   / / __ `/ | /| / /");
        System.out.println(" / ___ |/ /  / /__  / /___/ / /_/ /| |/ |/ / ");
        System.out.println("/_/  |_/_/   \\___/  \\____/_/\\__,_/ |__/|__/  ");
        System.out.println();
        System.out.println("  ArcClaw Agent Kernel :: Zero-Setup Distribution");
        System.out.println();
        System.out.println("  USAGE");
        System.out.println("    ./arcclaw serve                   Start HTTP + MCP server on :8080");
        System.out.println("    ./arcclaw chat                    Start interactive REPL");
        System.out.println("    ./arcclaw ask 'your goal here'    Run one-shot task and exit");
        System.out.println("    ./arcclaw attach                  Start Telegram daemon");
        System.out.println();
        System.out.println("  OPTIONS (append to any command)");
        System.out.println("    --role coordinator|researcher|architect|synthesizer|debugger");
        System.out.println();
    }

    // QuarkusApplication - runs AFTER CDI container is ready

    @Override
    public int run(String... args) {
        int exitCode = new CommandLine(this, factory).execute(args);
        setExitStatus(exitCode);
        return exitCode;
    }

    // Default action (no subcommand) - print help

    @Override
    public void run() {
        // Fallback for help/usage handled by Picocli when no subcommand matches
        new CommandLine(this, factory).usage(System.out);
    }
}
