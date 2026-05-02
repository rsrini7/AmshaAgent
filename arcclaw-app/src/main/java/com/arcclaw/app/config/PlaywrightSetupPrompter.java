package com.arcclaw.app.config;

import java.nio.file.Path;

/**
 * Handles Playwright setup prompting and auto-installation.
 */
public class PlaywrightSetupPrompter {

    public static void promptPlaywrightSetupIfNeeded(Path binaryDir) {
        if (System.getenv("CI") != null || System.getenv("GITHUB_ACTIONS") != null) return;

        Path browsersPath = binaryDir.resolve(".playwright-browsers");
        String playwrightProp = System.getProperty("ARCCLAW_TOOLS_PLAYWRIGHT_ENABLED",
                System.getenv("ARCCLAW_TOOLS_PLAYWRIGHT_ENABLED"));
        boolean playwrightEnabled = "true".equalsIgnoreCase(playwrightProp);

        if (playwrightEnabled && !PlaywrightBrowserInstaller.isChromiumInstalled(browsersPath)) {
            System.err.println("[ArcClaw] ℹ️  Auto-installing Chromium for Playwright...");
            PlaywrightBrowserInstaller.installChromium(browsersPath);
        }
    }
}
