package com.amsha.app;

import ai.djl.huggingface.tokenizers.jni.LibUtils;
import ai.onnxruntime.OrtEnvironment;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import jakarta.inject.Inject;

import com.amsha.app.cli.AttachCommand;
import com.amsha.app.cli.ChatCommand;
import com.amsha.app.cli.ModelsCommand;
import com.amsha.app.config.AppBootstrap;
import com.amsha.app.util.CliUtils;
import com.amsha.common.config.ConfigKeys;

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

        // 3.1 Fast-path for models management - run immediately without booting Quarkus engine
        if ("models".equalsIgnoreCase(args[0])) {
            String[] subArgs = new String[args.length - 1];
            System.arraycopy(args, 1, subArgs, 0, subArgs.length);
            int code = new CommandLine(new ModelsCommand()).execute(subArgs);
            System.exit(code);
        }

        // 3.5. Preload ONNX Runtime JNI library to avoid class initialization deadlocks
        // during Quarkus boot, especially when the JNI library invokes logging hooks
        // inside the OrtEnvironment static initializer.
        if (Boolean.parseBoolean(System.getProperty("amsha.onnx.enabled", System.getenv(ConfigKeys.ONNX_ENABLED)))) {
            try {
                // Trigger ai.onnxruntime.OnnxRuntime.<clinit> which calls System.load()
                OrtEnvironment.getEnvironment();
            } catch (Throwable _) {
                // Quarkus reports the definitive ONNX startup failure.
            }

            try {
                LibUtils.checkStatus();
            } catch (Throwable _) {
                // Quarkus reports the definitive tokenizer startup failure.
            }
        }

        // 4. Boot Quarkus - config system, CDI, and all extensions start here.
        Quarkus.run(AmshaCli.class, args);
        System.exit(exitStatus);
    }

    private static void printUsageGuide() {
        // ASCII Banner (from amsha-banner.txt)
        System.out.println("    ___                ________  ");
        System.out.println("   /   |  __________  / ____/ /___ __      __");
        System.out.println("  / /| | / ___/ ___/ / /   / / __ `/ | /| / /");
        System.out.println(" / ___ |/ /  / /__  / /___/ / /_/ /| |/ |/ / ");
        System.out.println("/_/  |_/_/   \\___/  \\____/_/\\__,_/ |__/|__/  ");
        System.out.println();
        System.out.println("  Amsha Agent Kernel :: Zero-Setup Distribution");
        System.out.println();
        System.out.println("  USAGE");
        System.out.println("    ./amsha serve                   Start HTTP + MCP server on :8080");
        System.out.println("    ./amsha chat                    Start interactive REPL");
        System.out.println("    ./amsha ask 'your goal here'    Run one-shot task and exit");
        System.out.println("    ./amsha attach                  Start Telegram daemon");
        System.out.println("    ./amsha models [list|install]   Manage local ONNX model bundles");
        System.out.println();
        System.out.println("  OPTIONS (append to any command)");
        System.out.println("    --role coordinator|researcher|architect|synthesizer|debugger");
        System.out.println("    --verbose Show token usage and agent actions");
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
