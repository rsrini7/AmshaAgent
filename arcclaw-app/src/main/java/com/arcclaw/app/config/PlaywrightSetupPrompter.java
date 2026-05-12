package com.arcclaw.app.config;

import java.nio.file.Path;

/**
 * Handles Playwright setup prompting and auto-installation.
 */
public class PlaywrightSetupPrompter {

    public static void promptPlaywrightSetupIfNeeded(Path binaryDir) {
        if (System.getenv("CI") != null || System.getenv("GITHUB_ACTIONS") != null) return;

        String playwrightProp = System.getProperty("ARCCLAW_TOOLS_PLAYWRIGHT_ENABLED",
                System.getenv("ARCCLAW_TOOLS_PLAYWRIGHT_ENABLED"));
        boolean playwrightEnabled = "true".equalsIgnoreCase(playwrightProp);

        if (!playwrightEnabled) return;

        PlaywrightCliInstaller.ensureBrowsersInstalled();
    }
}
