package com.amsha.app.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.amsha.common.config.ConfigKeys;
import com.amsha.common.util.LogUtil;

import org.jboss.logging.Logger;

/**
 * Orchestrates the application bootstrap sequence.
 */
public class BootstrapOrchestrator {

    private static final Logger log = Logger.getLogger(BootstrapOrchestrator.class);

    private static final String PROP_DATA_DIR = ConfigKeys.DATA_DIR;

    public static void bootstrap(String[] args) {
        Path binaryDir = resolveBinaryDir();
        drainInputBuffer();
        EnvConfigInitializer.extractEnvExampleIfNeeded(binaryDir);

        reexecWithLdPreloadIfNeeded(args);
        EnvConfigInitializer.loadDotEnv(binaryDir);

        // Validate .env structure and report issues to user (read-only, no modifications)
        Path envFile = binaryDir.resolve(".env");
        if (Files.exists(envFile)) {
            EnvFileValidator.ValidationResult envResult = EnvFileValidator.validate(envFile);
            EnvFileValidator.printReport(envResult, envFile);
        }

        normalizePlaywrightPath(binaryDir);

        // Informational invocations (--help/--version) must be side-effect free. Without
        // this guard `amsha --help` installs @playwright/cli globally and then blocks on
        // the first-run wizard before picocli ever prints usage. Validation and directory
        // provisioning below still run because Quarkus boots afterwards either way.
        boolean informational = isInformational(args);
        if (!informational) {
            PlaywrightCliInstaller.ensureCliInstalled();
        }

        ensureFallbackLlmDefaults();
        EnvConfigInitializer.validateSecurityKeys(binaryDir);

        LlmConfigValidator.validateLlmConfig(binaryDir);
        EnvConfigInitializer.validateEmbeddingConfig(binaryDir);

        SandboxValidator.validateSandboxTools(binaryDir);
        MandatoryConfigValidator.validateMandatoryConfig();
        LocalModelValidator.validateLocalModels(binaryDir, args);

        if (!informational) {
            InteractiveSetupWizard.run(binaryDir, args);
            PlaywrightSetupPrompter.promptPlaywrightSetupIfNeeded(binaryDir);
        }

        provisionDataDirectory(binaryDir);
        provisionNativeLibraries(binaryDir);
    }

    /**
     * Detects informational-only invocations, which must not mutate the environment,
     * install external tooling, or block on input. Mirrors the short-circuit used by
     * {@link com.amsha.app.util.CliUtils#validateRequiredEnvVars(String[])} and
     * {@link LocalModelValidator#validateLocalModels(Path, String[])}.
     */
    private static boolean isInformational(String[] args) {
        if (args == null || args.length == 0) return false;
        String mode = args[0].toLowerCase(Locale.ROOT);
        return mode.equals("--help") || mode.equals("-h") || mode.equals("--version") || mode.equals("-v");
    }

    private static Path resolveBinaryDir() {
        if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
            String command = ProcessHandle.current().info().command().orElse("");
            if (!command.isEmpty()) {
                try {
                    Path p = Path.of(command).toAbsolutePath().normalize().getParent();
                    if (Files.exists(p)) return p;
                } catch (Exception e) {
                    log.debugf("Failed to resolve binary dir: %s", LogUtil.rootMessage(e));
                }
            }
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath();
    }

    private static void reexecWithLdPreloadIfNeeded(String[] args) {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (!os.contains("linux")
                || System.getenv("AMSHA_LD_PRELOAD_SET") != null
                || System.getenv("LD_PRELOAD") != null) return;

        String libmPath = findLibm();
        if (libmPath == null) return;

        String command = ProcessHandle.current().info().command().orElse("");
        if (command.isEmpty()) return;

        try {
            ProcessBuilder pb = new ProcessBuilder();
            pb.command().add(command);
            for (String arg : args) pb.command().add(arg);
            pb.environment().put("LD_PRELOAD", libmPath);
            pb.environment().put("AMSHA_LD_PRELOAD_SET", "1");
            pb.inheritIO();
            System.exit(pb.start().waitFor());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while re-exec-ing with LD_PRELOAD", LogUtil.relevantThrowable(e));
        } catch (Exception e) {
            log.error("Failed to re-exec with LD_PRELOAD", LogUtil.relevantThrowable(e));
        }
    }

    private static String findLibm() {
        String[] candidates = {
            "/lib/x86_64-linux-gnu/libm.so.6",
            "/lib64/libm.so.6",
            "/usr/lib/x86_64-linux-gnu/libm.so.6",
            "/lib/aarch64-linux-gnu/libm.so.6"
        };
        for (String c : candidates) {
            if (Files.exists(Path.of(c))) return c;
        }
        return null;
    }

    private static void provisionDataDirectory(Path binaryDir) {
        String rawDataDir = System.getProperty(
                PROP_DATA_DIR,
                System.getenv()
                        .getOrDefault(PROP_DATA_DIR, binaryDir.resolve("data").toString()));
        try {
            Path dirPath = Path.of(rawDataDir).toAbsolutePath().normalize();
            if (!Files.exists(dirPath)) {
                Files.createDirectories(dirPath);
                Files.createDirectories(dirPath.resolve("workspaces"));
            }
            System.setProperty(PROP_DATA_DIR, dirPath.toString());
        } catch (IOException e) {
            System.err.println("[Amsha Agent] ⚠️  Warning: Failed to create data directory: " + e.getMessage());
        }
    }

    private static void provisionNativeLibraries(Path binaryDir) {
        String vecPath = System.getProperty(
                "AMSHA_RAG_VEC_PATH",
                System.getenv()
                        .getOrDefault(
                                "AMSHA_RAG_VEC_PATH",
                                binaryDir.resolve("lib/vec0").toString()));
        try {
            Path absVecPath = Path.of(vecPath).toAbsolutePath().normalize();
            if (vecPath.contains("vec0")) {
                Vec0NativeLibraryInstaller.performInstallation(absVecPath.toString());
            }
            System.setProperty("AMSHA_RAG_VEC_PATH", absVecPath.toString());
        } catch (Exception e) {
            System.err.println("[Amsha Agent] ⚠️  Warning: Failed to provision native libs: " + e.getMessage());
        }
    }

    private static void normalizePlaywrightPath(Path binaryDir) {
        System.setProperty("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "0");
        String path = System.getProperty("PLAYWRIGHT_BROWSERS_PATH");
        if (path != null && !path.isBlank()) {
            Path p = Path.of(path);
            if (!p.isAbsolute()) {
                System.setProperty(
                        "PLAYWRIGHT_BROWSERS_PATH",
                        binaryDir.resolve(p).toAbsolutePath().normalize().toString());
            }
        }
    }

    private static void ensureFallbackLlmDefaults() {
        String[] keys = {
            "amsha.llm.fallback-provider",
            "amsha.llm.fallback-model",
            "amsha.llm.fallback-base-url",
            "amsha.llm.fallback-api-key"
        };
        for (String k : keys) {
            if (System.getProperty(k) == null) System.setProperty(k, "");
        }
    }

    private static void drainInputBuffer() {
        try {
            while (System.in.available() > 0) System.in.read();
        } catch (IOException e) {
            log.warn("Failed to drain input buffer", LogUtil.relevantThrowable(e));
        }
    }
}
