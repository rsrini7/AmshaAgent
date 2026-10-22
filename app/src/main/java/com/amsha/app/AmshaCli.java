package com.amsha.app;

import ai.djl.huggingface.tokenizers.jni.LibUtils;
import ai.onnxruntime.OrtEnvironment;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import jakarta.inject.Inject;

import java.io.PrintStream;

import com.amsha.app.cli.AttachCommand;
import com.amsha.app.cli.ChatCommand;
import com.amsha.app.cli.CliArguments;
import com.amsha.app.cli.CliStartupFailureReporter;
import com.amsha.app.cli.ModelInstaller;
import com.amsha.app.cli.ModelsCommand;
import com.amsha.app.config.AppBootstrap;
import com.amsha.app.config.LocalModelValidator;
import com.amsha.app.util.CliUtils;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * Amsha Agent Dual-Mode Entry Point.
 * <p>
 * Routes to either {@link AskCommand} (CLI one-shot) or
 * {@link ServeCommand} (daemon/REST API) via picocli subcommands.
 * <p>
 * <strong>Data-dir lifecycle:</strong> The static {@code main()} method
 * provisions {@code AMSHA_DATA_DIR} <em>before</em> {@code Quarkus.run()}
 * is called, ensuring the directory exists before Quarkus boots.
 * The raw value is resolved to an absolute, normalised path and written
 * back into {@code System.setProperty} so that every
 * {@code ${AMSHA_DATA_DIR:./data}} expression in {@code application.yaml}
 * (datasource URL, amsha.data-dir, workspace root, etc.) resolves to the
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
        name = "amsha",
        mixinStandardHelpOptions = true,
        subcommands = {AskCommand.class, ServeCommand.class, ChatCommand.class, AttachCommand.class, ModelsCommand.class
        })
@SuppressWarnings("java:S106")
public class AmshaCli implements Runnable, QuarkusApplication {

    @Inject
    CommandLine.IFactory factory;

    private static final int EXIT_STATUS_NOT_SET = Integer.MIN_VALUE;

    private static volatile int exitStatus = EXIT_STATUS_NOT_SET;

    private static void setExitStatus(int code) {
        exitStatus = code;
    }

    public static void main(String... args) {
        if (args == null || args.length == 0 || isRootHelp(args[0])) {
            printUsageGuide();
            return;
        }
        if (isVersion(args[0])) {
            printVersion();
            return;
        }
        System.exit(runApplication(args));
    }

    private static int runApplication(String[] args) {
        String stage = "startup";
        try {
            if (args.length > 0 && CliArguments.COMMAND_MODELS.equalsIgnoreCase(args[0])) {
                stage = "model management setup";
                AppBootstrap.bootstrapModelManagement();
                String[] subArgs = new String[args.length - 1];
                System.arraycopy(args, 1, subArgs, 0, subArgs.length);
                CommandLine modelCommand = new CommandLine(new ModelsCommand());
                modelCommand.setExecutionExceptionHandler((exception, _, _) -> {
                    CliStartupFailureReporter.report("models command", exception, System.err);
                    return 1;
                });
                return modelCommand.execute(subArgs);
            }

            stage = "application bootstrap";
            AppBootstrap.bootstrap(args);

            stage = "configuration validation";
            CliUtils.validateRequiredEnvVars(args);

            if (LocalModelValidator.shouldInitializeOnnxRuntime()) {
                stage = "local ONNX runtime initialization";
                OrtEnvironment.getEnvironment();
                LibUtils.checkStatus();
            }

            exitStatus = EXIT_STATUS_NOT_SET;
            stage = "Quarkus runtime startup";
            String quarkusStage = stage;
            Quarkus.run(
                    AmshaCli.class,
                    (code, failure) -> handleQuarkusExit(code, failure, quarkusStage, System.err),
                    args);
            if (exitStatus == EXIT_STATUS_NOT_SET) {
                CliStartupFailureReporter.report(
                        stage,
                        new IllegalStateException("Quarkus returned before the Amsha CLI command handler started."),
                        System.err);
                return 1;
            }
            return exitStatus;
        } catch (LocalModelValidator.StartupPreflightException failure) {
            CliStartupFailureReporter.report("local-model preflight", failure, System.err);
            return 2;
        } catch (VirtualMachineError | ThreadDeath fatalFailure) {
            throw fatalFailure;
        } catch (Throwable failure) {
            CliStartupFailureReporter.report(stage, failure, System.err);
            return 1;
        }
    }

    static void handleQuarkusExit(int code, Throwable failure, String stage, PrintStream errorOutput) {
        if (failure instanceof VirtualMachineError fatalFailure) {
            throw fatalFailure;
        }
        if (failure instanceof ThreadDeath fatalFailure) {
            throw fatalFailure;
        }
        if (failure != null) {
            CliStartupFailureReporter.report(stage, failure, errorOutput);
        }
        setExitStatus(code);
    }

    private static void printUsageGuide() {
        System.out.println(" ___ ________ ");
        System.out.println(" / | __________ / ____/ /___ __ __");
        System.out.println(" / /| | / ___/ ___/ / / / / __ `/ | /| / /");
        System.out.println(" / ___ |/ / / /__ / /___/ / /_/ /| |/ |/ / ");
        System.out.println("/_/ |_/_/ \\___/ \\____/_/\\__,_/ |__/|__/ ");
        System.out.println();
        System.out.println("Amsha Agent :: commands and options");
        System.out.println();

        String launcher = ModelInstaller.launcherCommand();
        System.out.println("USAGE");
        System.out.printf("  %s <command> [options]%n", launcher);
        System.out.println();
        System.out.println("COMMANDS");
        System.out.printf(
                "  %-48s %s%n", launcher + " serve", "Start the REST API and MCP server (HTTP port 8080 by default)");
        System.out.printf("  %-48s %s%n", launcher + " chat [options]", "Start an interactive agent session");
        System.out.printf("  %-48s %s%n", launcher + " ask <goal> [options]", "Run one task and exit");
        System.out.printf("  %-48s %s%n", launcher + " attach <channel>", "Run a channel daemon (telegram, discord)");
        System.out.printf("  %-48s %s%n", launcher + " models [command]", "Manage optional local model bundles");
        System.out.println();
        System.out.println("ROOT OPTIONS");
        System.out.println("  -h, --help                     Show this command guide");
        System.out.println("  -V, -v, --version              Print the application version");
        System.out.println();
        System.out.println("CHAT OPTIONS");
        System.out.println("  -r, --role <role>              Select an agent role");
        System.out.println("                                 Built-ins: coder, debugger, researcher, coordinator,");
        System.out.println("                                 architect, synthesizer, optimizer, sentinel, healer,");
        System.out.println(
                "                                 protector, purifier, nurturer; configured roles may add more.");
        System.out.println("  -s, --session-id <id>          Resume a session by ID");
        System.out.println("  --last-session                 Resume the most recently active CLI session");
        System.out.println("  --new-session                  Start a fresh session (the default)");
        System.out.println();
        System.out.println("ASK OPTIONS");
        System.out.println("  -r, --role <role>              Select an agent role");
        System.out.println("  --verbose                      Show token usage and agent actions");
        System.out.println();
        System.out.println("ATTACH OPTIONS");
        System.out.println("  <channel>                      telegram or discord");
        System.out.println();
        System.out.println("MODEL OPTIONS");
        System.out.printf("  %s models list%n", launcher);
        System.out.printf("  %s models install <model>    Download whisper, mpnet, bge-reranker, or all%n", launcher);
        System.out.printf("  %s models verify [model|all] Verify installed bundle checksums%n", launcher);
        System.out.println("  Bundle sizes: whisper ~43 MB, mpnet ~436 MB, bge-reranker ~1.1 GB; all ~1.6 GB.");
        System.out.println("  Downloads need internet and are saved under the configured model root.");
        System.out.println();
        System.out.println("HELP AND SETUP");
        System.out.printf("  %s --help%n", launcher);
        System.out.printf("  %s models --help%n", launcher);
        System.out.println("  Put .env beside the binary and configure an LLM provider before running agent commands.");
        System.out.println("  ONNX is optional and downloads only when you run models install.");
        System.out.println("  To use ONNX embeddings, enable it and select the onnx embedding provider in .env.");
        System.out.println();
    }

    private static boolean isRootHelp(String arg) {
        return CliArguments.isHelpOption(arg);
    }

    private static boolean isVersion(String arg) {
        return CliArguments.isVersionOption(arg);
    }

    private static void printVersion() {
        String version = AmshaCli.class.getPackage().getImplementationVersion();
        System.out.printf("Amsha Agent %s%n", version == null || version.isBlank() ? "version unavailable" : version);
    }

    // QuarkusApplication - runs AFTER CDI container is ready

    @Override
    public int run(String... args) {
        CommandLine commandLine = new CommandLine(this, factory);
        commandLine.setExecutionExceptionHandler((exception, _, _) -> {
            CliStartupFailureReporter.report("command execution", exception, System.err);
            return 1;
        });
        int exitCode = commandLine.execute(args);
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
