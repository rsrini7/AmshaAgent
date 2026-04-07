package com.arcclaw.tool.playwright.config;

import com.arcclaw.tool.playwright.PlaywrightBrowserTool;
import com.arcclaw.tool.playwright.PlaywrightPageRunner;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Ensures Playwright tool components and underlying library drivers are preserved
 * for reflection during GraalVM Native Image compilation.
 */
@RegisterForReflection(
    targets = {
        PlaywrightBrowserTool.class,
        PlaywrightPageRunner.class
    },
    classNames = {
        "com.microsoft.playwright.CLI",
        "com.microsoft.playwright.impl.driver.Driver",
        "com.microsoft.playwright.impl.driver.jar.DriverJar"
    }
)
public class PlaywrightReflectionConfig {
    // Container for @RegisterForReflection
}
