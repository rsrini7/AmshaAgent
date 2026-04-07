package com.arcclaw.app.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

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
            return files.anyMatch(p -> Files.isDirectory(p) && p.getFileName().toString().startsWith("chromium-"));
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
                runClassInstaller(browsersPath);
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
        // Fallback for binary distributions where mvnw is missing
        String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + "/bin/java";
        String classpath = System.getProperty("java.class.path");

        List<String> command = new ArrayList<>();
        command.add(javaBin);
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
}
