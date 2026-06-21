package com.arcclaw.app.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import com.arcclaw.common.util.LogUtil;

import org.jboss.logging.Logger;

/**
 * Auto-installs the @playwright/cli Node.js package and browser binaries.
 * Replaces the old Playwright Java library driver-extraction approach.
 */
public class PlaywrightCliInstaller {

    private static final Logger log = Logger.getLogger(PlaywrightCliInstaller.class);

    private static final String NPM_INSTALL_TIMEOUT_SEC = "120";
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
            System.err.println("     Or set ARCCLAW_TOOLS_PLAYWRIGHT_ENABLED=false to disable.");
            System.err.println();
            return;
        }

        installPlaywrightCli();
    }

    public static void ensureBrowsersInstalled() {
        if (!isPlaywrightCliAvailable()) return;

        String skipInstall = System.getProperty(
                "ARCCLAW_TOOLS_PLAYWRIGHT_SKIP_INSTALL", System.getenv("ARCCLAW_TOOLS_PLAYWRIGHT_SKIP_INSTALL"));
        if ("true".equalsIgnoreCase(skipInstall)) {
            log.info("Playwright browser install skipped (ARCCLAW_TOOLS_PLAYWRIGHT_SKIP_INSTALL=true)");
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
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = p.waitFor();

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
            log.error("Playwright CLI auto-install failed", e);
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
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();
        if (exit != 0) throw new IOException("exit " + exit + ": " + out.trim());
        return out;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
