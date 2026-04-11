package com.arcclaw.app.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import org.jboss.logging.Logger;

/**
 * Handles the idempotent installation of Playwright browser binaries (specifically Chromium).
 * 
 * <p>Addressing module boundaries: Since the Playwright dependency is contained within the 
 * 'arcclaw-tool-playwright' module, we attempt to use 'mvnw' if available in the source tree 
 * to ensure all dependencies are correctly resolved via Maven. For binary distributions, 
 * we fall back to invoking the CLI from the current classpath.
 */
public class PlaywrightBrowserInstaller {

    private static final Logger log = Logger.getLogger(PlaywrightBrowserInstaller.class);

    /**
     * Checks if Chromium is already installed in the specified path.
     * Looks for any directory starting with 'chromium-' in the browsers path.
     */
    public static boolean isChromiumInstalled(Path browsersPath) {
        if (!Files.exists(browsersPath)) return false;
        try (Stream<Path> files = Files.list(browsersPath)) {
            return files.anyMatch(p -> {
                String name = p.getFileName().toString();
                return Files.isDirectory(p) && (name.startsWith("chromium-") || name.startsWith("chromium_headless_shell-"));
            });
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Installs Playwright Chromium browser.
     * Prefers using 'mvnw' if in a source tree; otherwise uses the current Java runtime.
     */
    public static void installChromium(Path browsersPath) {
        System.err.println();
        System.err.println("  → Installing Playwright Chromium into " + browsersPath + " ...");
        System.err.println("    (This may take a few minutes depending on your connection)");
        System.err.println();

        try {
            // Check for Maven Wrapper (mvnw) in CWD or parent (source tree detection)
            Path cwd = Path.of(System.getProperty("user.dir"));
            Path mvnw = findMvnw(cwd);

            if (mvnw != null) {
                runMavenInstaller(mvnw, browsersPath);
            } else {
                runBinaryInstaller(browsersPath);
            }
        } catch (Exception e) {
            System.err.println();
            System.err.println("  ✗ Playwright Chromium installation failed: " + e.getMessage());
            log.error("Playwright installation failure", e);
        }
    }

    private static Path findMvnw(Path start) {
        Path p = start;
        while (p != null) {
            Path candidate = p.resolve("mvnw");
            if (Files.exists(candidate)) return candidate;
            p = p.getParent();
        }
        return null;
    }

    private static void runMavenInstaller(Path mvnw, Path browsersPath) throws Exception {
        List<String> command = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase();
        
        if (os.contains("win")) {
            command.add("cmd.exe");
            command.add("/c");
            command.add("mvnw.cmd");
        } else {
            command.add("./mvnw");
        }

        command.add("exec:java");
        command.add("-e");
        command.add("-pl");
        command.add("arcclaw-tool-playwright");
        command.add("-Dexec.mainClass=com.microsoft.playwright.CLI");
        command.add("-Dexec.args=install --with-deps chromium");
        command.add("-DPLAYWRIGHT_BROWSERS_PATH=" + browsersPath.toString());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(mvnw.getParent().toFile()); // Run from project root
        pb.inheritIO();
        Process p = pb.start();
        int exitCode = p.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("Maven installer returned exit code " + exitCode);
        }

        System.err.println();
        System.err.println("  ✓ Playwright Chromium installed successfully.");

        // In promptPlaywrightSetupIfNeeded(), after installChromium() call:
        System.err.println();
        System.err.println("  ─────────────────────────────────────────────────────");
        System.err.println("  ✓ Browser ready — starting ArcClaw...");
        System.err.println("  ─────────────────────────────────────────────────────");
        System.err.println();
    }

    private static void runClassInstaller(Path browsersPath) throws Exception {
        String classpath = System.getProperty("java.class.path");
        if (classpath == null || classpath.isBlank()) {
            throw new RuntimeException("java.class.path is unavailable");
        }

        List<String> command = new ArrayList<>();
        command.add(resolveJavaExecutable());
        command.add("-cp");
        command.add(classpath);
        command.add("-DPLAYWRIGHT_BROWSERS_PATH=" + browsersPath.toString());
        command.add("com.microsoft.playwright.CLI");
        command.add("install");
        command.add("--with-deps");
        command.add("chromium");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.inheritIO();
        Process p = pb.start();
        int exitCode = p.waitFor();

        if (exitCode == 0) {
            System.err.println();
            System.err.println("  ✓ Playwright Chromium installed successfully.");
        } else {
            throw new RuntimeException("CLI returned exit code " + exitCode);
        }
    }

    private static void runBinaryInstaller(Path browsersPath) throws Exception {
        StringBuilder attempts = new StringBuilder();
        try {
            runClassInstaller(browsersPath);
            return;
        } catch (Exception e) {
            attempts.append("classpath installer: ").append(safeMessage(e));
        }

        try {
            runSystemPlaywrightInstaller(browsersPath);
            return;
        } catch (Exception e) {
            if (!attempts.isEmpty()) attempts.append(" | ");
            attempts.append("system playwright CLI: ").append(safeMessage(e));
        }

        try {
            runNpxPlaywrightInstaller(browsersPath);
            return;
        } catch (Exception e) {
            if (!attempts.isEmpty()) attempts.append(" | ");
            attempts.append("npx playwright CLI: ").append(safeMessage(e));
        }

        try {
            runInProcessInstaller(browsersPath);
            return;
        } catch (Exception e) {
            if (!attempts.isEmpty()) attempts.append(" | ");
            attempts.append("in-process installer: ").append(safeMessage(e));
        }

        throw new RuntimeException(
                "Could not install Chromium in binary mode. Tried: " + attempts
                + ". Install from source checkout with ./mvnw exec:java -pl arcclaw-tool-playwright "
                + "-Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args=\"install --with-deps chromium\"");
    }

    private static void runSystemPlaywrightInstaller(Path browsersPath) throws Exception {
        List<String> command = new ArrayList<>();
        if (isWindows()) {
            command.add("cmd.exe");
            command.add("/c");
            command.add("playwright");
        } else {
            command.add("playwright");
        }
        command.add("install");
        command.add("chromium");
        runExternalInstaller(command, browsersPath, "system playwright CLI");
    }

    private static void runNpxPlaywrightInstaller(Path browsersPath) throws Exception {
        List<String> command = new ArrayList<>();
        if (isWindows()) {
            command.add("cmd.exe");
            command.add("/c");
            command.add("npx");
        } else {
            command.add("npx");
        }
        command.add("--yes");
        command.add("playwright");
        command.add("install");
        command.add("chromium");
        runExternalInstaller(command, browsersPath, "npx playwright CLI");
    }

    private static void runExternalInstaller(List<String> command, Path browsersPath, String label) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("PLAYWRIGHT_BROWSERS_PATH", browsersPath.toString());
        pb.environment().put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "0");
        pb.inheritIO();
        Process p = pb.start();
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException(label + " returned exit code " + exitCode);
        }
        System.err.println();
        System.err.println("  ✓ Playwright Chromium installed successfully.");
    }

    private static void runInProcessInstaller(Path browsersPath) {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("PLAYWRIGHT_BROWSERS_PATH", browsersPath.toString());
        env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "0");

        Playwright.CreateOptions options = new Playwright.CreateOptions().setEnv(env);
        try (Playwright playwright = Playwright.create(options)) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            browser.close();
            System.err.println();
            System.err.println("  ✓ Playwright Chromium installed successfully.");
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String safeMessage(Throwable t) {
        String msg = t.getMessage();
        return (msg == null || msg.isBlank()) ? t.getClass().getSimpleName() : msg;
    }

    private static String resolveJavaExecutable() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String javaName = os.contains("win") ? "java.exe" : "java";

        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            Path javaFromHome = Path.of(javaHome, "bin", javaName);
            if (Files.exists(javaFromHome)) return javaFromHome.toString();
        }

        String currentCommand = ProcessHandle.current().info().command().orElse("");
        if (!currentCommand.isBlank()) {
            Path commandPath = Path.of(currentCommand);
            Path fileName = commandPath.getFileName();
            if (fileName != null) {
                String runningName = fileName.toString().toLowerCase();
                if ("java".equals(runningName) || "java.exe".equals(runningName)) {
                    return commandPath.toString();
                }
            }
        }

        return javaName;
    }
}
