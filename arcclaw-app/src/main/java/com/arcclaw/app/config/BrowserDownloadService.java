package com.arcclaw.app.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import org.jboss.logging.Logger;

/**
 * Handles the download and installation of Playwright browser binaries.
 */
public class BrowserDownloadService {

    private static final Logger log = Logger.getLogger(BrowserDownloadService.class);

    /**
     * Installs Playwright Chromium browser.
     */
    public static void installChromium(Path browsersPath) {
        System.err.println();
        System.err.println("  → Installing Playwright Chromium into " + browsersPath + " ...");
        System.err.println("    (This may take a few minutes depending on your connection)");
        System.err.println();

        try {
            try {
                // FIRST: use extracted driver - guarantees version match with driver-bundle
                Path binaryDir = browsersPath.getParent();
                Path driverDir = binaryDir.resolve(".playwright-driver").resolve(BrowserHealthChecker.detectPlatform());
                Path nodeExe   = driverDir.resolve(isWindows() ? "node.exe" : "node");
                Path cliJs     = driverDir.resolve("package").resolve("cli.js");

                if (Files.exists(nodeExe) && Files.exists(cliJs)) {
                    try {
                        log.info("Installing Chromium via extracted driver (version-matched)");
                        List<String> command = new ArrayList<>();
                        command.add(nodeExe.toAbsolutePath().toString());
                        command.add(cliJs.toAbsolutePath().toString());
                        command.add("install");
                        command.add("chromium");
                        ProcessBuilder pb = new ProcessBuilder(command);
                        pb.environment().put("PLAYWRIGHT_BROWSERS_PATH", browsersPath.toString());
                        pb.environment().put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "0");
                        pb.inheritIO();
                        int exit = pb.start().waitFor();
                        if (exit == 0) {
                            printSuccessMessage();
                            return;
                        }
                    } catch (Exception e) {
                        log.warnf("Driver-based install failed (%s) - falling back...", safeMessage(e));
                    }
                }

                // FALLBACK: mvnw (source tree) or npx/system (binary distribution)
                Path cwd  = Path.of(System.getProperty("user.dir"));
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
        } finally {
            writeSentinelFile(browsersPath);
        }
    }

    private static void printSuccessMessage() {
        System.err.println();
        System.err.println("  ✓ Playwright Chromium installed successfully.");
        System.err.println();
        System.err.println("  ─────────────────────────────────────────────────────");
        System.err.println("  ✓ Browser ready - ArcClaw is ready to use!");
        System.err.println("  ─────────────────────────────────────────────────────");
        System.err.println();
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
        if (isWindows()) {
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
        pb.directory(mvnw.getParent().toFile());
        pb.inheritIO();
        Process p = pb.start();
        int exitCode = p.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("Maven installer returned exit code " + exitCode);
        }

        printSuccessMessage();
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
            "Could not install Chromium. Tried: " + attempts +
            ". Install manually: playwright install chromium");
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

    private static void runExternalInstaller(List<String> command, Path browsersPath, String label)
            throws Exception {
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
        String absolutePath = browsersPath.toAbsolutePath().normalize().toString();
        env.put("PLAYWRIGHT_BROWSERS_PATH", absolutePath);
        env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "0");

        log.infof("Running in-process Playwright installer with BROWSERS_PATH=%s", absolutePath);
        Playwright.CreateOptions options = new Playwright.CreateOptions().setEnv(env);
        try (Playwright playwright = Playwright.create(options)) {
            Browser browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(true));
            try {
                browser.close();
            } catch (Exception e) {
                log.debugf("Benign error during browser close in installer: %s", safeMessage(e));
            }
            System.err.println();
            System.err.println("  ✓ Playwright Chromium installed successfully.");
        }
    }

    private static void writeSentinelFile(Path browsersPath) {
        try {
            Files.createDirectories(browsersPath);
            Path sentinel = browsersPath.resolve(".arcclaw-playwright-version");
            String currentVersion = BrowserHealthChecker.detectPlaywrightVersion();
            Files.writeString(sentinel, currentVersion);
            log.infof("[BrowserDownloadService] Successfully wrote version sentinel file with version %s", currentVersion);
        } catch (IOException e) {
            log.warnf("[BrowserDownloadService] Could not write sentinel file: %s", e.getMessage());
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
        return javaName;
    }
}
