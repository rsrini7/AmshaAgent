package com.amsha.app.config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import com.amsha.common.util.LogUtil;

import org.jboss.logging.Logger;

/**
 * Auto-installs the @playwright/cli Node.js package and browser binaries.
 * Replaces the old Playwright Java library driver-extraction approach.
 */
public class PlaywrightCliInstaller {

    private static final Logger log = Logger.getLogger(PlaywrightCliInstaller.class);

    private static final String NPM_INSTALL_TIMEOUT_SEC = "120";
    private static final long INSTALL_TIMEOUT_SECONDS = 120;
    private static final long BROWSER_INSTALL_TIMEOUT_SECONDS = 300;
    private static final String NODE_MIN_VERSION = "20";

    public static void ensureCliInstalled() {
        if (isPlaywrightCliAvailable()) {
            log.debug("playwright-cli already available on PATH");
            return;
        }
        if (System.getenv("CI") != null || System.getenv("GITHUB_ACTIONS") != null) return;

        if (!isNodeAvailable()) {
            System.err.println();
            System.err.println("  ⚠️  Node.js " + NODE_MIN_VERSION + "+ is required for the browser tool.");
            System.err.println("     Install from: https://nodejs.org/");
            System.err.println("     Or set AMSHA_TOOLS_PLAYWRIGHT_ENABLED=false to disable.");
            System.err.println();
            return;
        }

        installPlaywrightCli();
    }

    public static void ensureBrowsersInstalled() {
        if (!isPlaywrightCliAvailable()) return;

        String skipInstall = System.getProperty(
                "AMSHA_TOOLS_PLAYWRIGHT_SKIP_INSTALL", System.getenv("AMSHA_TOOLS_PLAYWRIGHT_SKIP_INSTALL"));
        if ("true".equalsIgnoreCase(skipInstall)) {
            log.info("Playwright browser install skipped (AMSHA_TOOLS_PLAYWRIGHT_SKIP_INSTALL=true)");
            return;
        }

        if (isChromiumInstalled()) {
            log.debug("Chromium browser already installed");
            return;
        }

        try {
            System.err.println("  → Installing chromium browser…");
            String browsersOut = exec(List.of("playwright-cli", "install-browser", "chromium"));
            if (browsersOut != null && !browsersOut.isBlank()) {
                log.infof("Playwright browsers: %s", browsersOut.trim());
            }
            System.err.println("  ✓ chromium browser installed.");
        } catch (Exception e) {
            log.warnf("Playwright browser install failed (may already be installed): %s", LogUtil.rootMessage(e));
        }
    }

    private static boolean isChromiumInstalled() {
        try {
            String out = exec(List.of("playwright-cli", "install-browser", "--list"));
            return out != null && out.contains("chromium");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isPlaywrightCliAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(platformCommand(List.of("playwright-cli", "--version")));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isNodeAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(platformCommand(List.of("node", "--version")));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            if (p.exitValue() != 0) return false;
            String ver = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return ver.startsWith("v") && Integer.parseInt(ver.substring(1, ver.indexOf('.'))) >= 20;
        } catch (Exception e) {
            return false;
        }
    }

    private static void installPlaywrightCli() {
        System.err.println();
        System.err.println("  → Installing @playwright/cli globally...");

        try {
            List<String> npmCmd = new ArrayList<>();
            if (isWindows()) {
                npmCmd.add("cmd.exe");
                npmCmd.add("/c");
            }
            npmCmd.add("npm");
            npmCmd.add("install");
            npmCmd.add("-g");
            npmCmd.add("@playwright/cli");

            ProcessBuilder pb = new ProcessBuilder(npmCmd);
            pb.redirectErrorStream(true);
            pb.environment().put("NPM_CONFIG_TIMEOUT", NPM_INSTALL_TIMEOUT_SEC);
            CommandResult result = runBounded(pb, INSTALL_TIMEOUT_SECONDS);
            int exit = result.exitCode();
            String output = result.output();

            if (exit == 0) {
                System.err.println("  ✓ @playwright/cli installed.");
                ensureBrowsersInstalled();
            } else {
                System.err.println("  ✗ npm install failed (exit " + exit + "):");
                System.err.println("    " + output.trim());
                System.err.println();
                System.err.println(
                        "  Install manually: npm install -g @playwright/cli && playwright-cli install-browser chromium");
            }
        } catch (Exception e) {
            System.err.println("  ✗ Failed to install @playwright/cli: " + e.getMessage());
            log.error("Playwright CLI auto-install failed", LogUtil.relevantThrowable(e));
        }
    }

    private static List<String> platformCommand(List<String> cmd) {
        if (isWindows()) {
            List<String> wrapped = new ArrayList<>();
            wrapped.add("cmd.exe");
            wrapped.add("/c");
            wrapped.addAll(cmd);
            return wrapped;
        }
        return cmd;
    }

    private static String exec(List<String> cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(platformCommand(cmd));
        pb.redirectErrorStream(true);
        CommandResult result = runBounded(pb, BROWSER_INSTALL_TIMEOUT_SECONDS);
        if (result.exitCode() != 0) {
            throw new IOException(
                    "exit " + result.exitCode() + ": " + result.output().trim());
        }
        return result.output();
    }

    /**
     * Runs a command with a hard timeout, draining stdout on a virtual thread so a chatty
     * child process cannot deadlock against a full pipe buffer. Returns exit code {@code -1}
     * when the timeout elapses (the process is destroyed first).
     */
    private static CommandResult runBounded(ProcessBuilder pb, long timeoutSeconds)
            throws IOException, InterruptedException {
        Process p = pb.start();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        Thread drainer = Thread.ofVirtual().start(() -> {
            try (InputStream in = p.getInputStream()) {
                in.transferTo(sink);
            } catch (IOException _) {
                // Child exited early; whatever was buffered is enough for diagnostics.
            }
        });

        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            p.waitFor(5, TimeUnit.SECONDS);
            drainer.join(1000);
            return new CommandResult(-1, "timed out after " + timeoutSeconds + "s");
        }
        drainer.join(5000);
        return new CommandResult(p.exitValue(), sink.toString(StandardCharsets.UTF_8));
    }

    private record CommandResult(int exitCode, String output) {}

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
