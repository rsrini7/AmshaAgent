package com.arcclaw.app.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.arcclaw.common.util.LogUtil;
import org.jboss.logging.Logger;

/**
 * Orchestrates the application bootstrap sequence.
 */
public class BootstrapOrchestrator {

    private static final Logger log = Logger.getLogger(BootstrapOrchestrator.class);

    public static void bootstrap(String[] args) {
        Path binaryDir = resolveBinaryDir();
        drainInputBuffer();
        EnvConfigInitializer.extractEnvExampleIfNeeded(binaryDir);
        
        reexecWithLdPreloadIfNeeded(args);
        EnvConfigInitializer.loadDotEnv(binaryDir);
        
        normalizePlaywrightPath(binaryDir);
        PlaywrightCliInstaller.ensureCliInstalled();

        ensureFallbackLlmDefaults();
        EnvConfigInitializer.validateAndAutoFixSecurityKeys(binaryDir);
        
        LlmConfigAutoFixer.validateAndAutoFixLlmConfig(binaryDir);
        EnvConfigInitializer.validateAndAutoFixEmbeddingConfig(binaryDir);
        
        SandboxValidator.validateSandboxTools(binaryDir);
        MandatoryConfigValidator.validateMandatoryConfig();
        
        InteractiveSetupWizard.run(binaryDir, args);
        PlaywrightSetupPrompter.promptPlaywrightSetupIfNeeded(binaryDir);
        
        provisionDataDirectory(binaryDir);
        provisionNativeLibraries(binaryDir);
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
        String os = System.getProperty("os.name").toLowerCase();
        if (!os.contains("linux") || System.getenv("ARCCLAW_LD_PRELOAD_SET") != null || System.getenv("LD_PRELOAD") != null) return;

        String libmPath = findLibm();
        if (libmPath == null) return;

        String command = ProcessHandle.current().info().command().orElse("");
        if (command.isEmpty()) return;

        try {
            ProcessBuilder pb = new ProcessBuilder();
            pb.command().add(command);
            for (String arg : args) pb.command().add(arg);
            pb.environment().put("LD_PRELOAD", libmPath);
            pb.environment().put("ARCCLAW_LD_PRELOAD_SET", "1");
            pb.inheritIO();
            System.exit(pb.start().waitFor());
        } catch (Exception e) {
            log.error("Failed to re-exec with LD_PRELOAD", e);
        }
    }

    private static String findLibm() {
        String[] candidates = {"/lib/x86_64-linux-gnu/libm.so.6", "/lib64/libm.so.6", "/usr/lib/x86_64-linux-gnu/libm.so.6", "/lib/aarch64-linux-gnu/libm.so.6"};
        for (String c : candidates) {
            if (Files.exists(Path.of(c))) return c;
        }
        return null;
    }

    private static void provisionDataDirectory(Path binaryDir) {
        String rawDataDir = System.getProperty("ARCCLAW_DATA_DIR", 
                System.getenv().getOrDefault("ARCCLAW_DATA_DIR", binaryDir.resolve("data").toString()));
        try {
            Path dirPath = Path.of(rawDataDir).toAbsolutePath().normalize();
            if (!Files.exists(dirPath)) {
                Files.createDirectories(dirPath);
                Files.createDirectories(dirPath.resolve("workspaces"));
            }
            System.setProperty("ARCCLAW_DATA_DIR", dirPath.toString());
        } catch (IOException e) {
            System.err.println("[ArcClaw] ⚠️  Warning: Failed to create data directory: " + e.getMessage());
        }
    }

    private static void provisionNativeLibraries(Path binaryDir) {
        String vecPath = System.getProperty("ARCCLAW_RAG_VEC_PATH",
                System.getenv().getOrDefault("ARCCLAW_RAG_VEC_PATH", binaryDir.resolve("lib/vec0").toString()));
        try {
            Path absVecPath = Path.of(vecPath).toAbsolutePath().normalize();
            if (vecPath.contains("vec0")) {
                Vec0NativeLibraryInstaller.performInstallation(absVecPath.toString());
            }
            System.setProperty("ARCCLAW_RAG_VEC_PATH", absVecPath.toString());
        } catch (Exception e) {
            System.err.println("[ArcClaw] ⚠️  Warning: Failed to provision native libs: " + e.getMessage());
        }
    }

    private static void normalizePlaywrightPath(Path binaryDir) {
        System.setProperty("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "0");
        String path = System.getProperty("PLAYWRIGHT_BROWSERS_PATH");
        if (path != null && !path.isBlank()) {
            Path p = Path.of(path);
            if (!p.isAbsolute()) {
                System.setProperty("PLAYWRIGHT_BROWSERS_PATH", binaryDir.resolve(p).toAbsolutePath().normalize().toString());
            }
        }
    }

    private static void ensureFallbackLlmDefaults() {
        String[] keys = {"arcclaw.llm.fallback-provider", "arcclaw.llm.fallback-model", "arcclaw.llm.fallback-base-url", "arcclaw.llm.fallback-api-key"};
        for (String k : keys) {
            if (System.getProperty(k) == null) System.setProperty(k, "");
        }
    }

    private static void drainInputBuffer() {
        try {
            while (System.in.available() > 0) System.in.read();
        } catch (IOException e) {
            log.warn("Failed to drain input buffer", e);
        }
    }
}
