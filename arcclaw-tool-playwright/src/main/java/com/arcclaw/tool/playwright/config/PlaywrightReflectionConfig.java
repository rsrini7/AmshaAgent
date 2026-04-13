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
        "com.microsoft.playwright.impl.driver.jar.DriverJar",
        "com.microsoft.playwright.impl.Message",
        "com.microsoft.playwright.impl.Connection",
        "com.microsoft.playwright.impl.PlaywrightImpl",
        "com.microsoft.playwright.impl.BrowserImpl",
        "com.microsoft.playwright.impl.BrowserTypeImpl",
        "com.microsoft.playwright.impl.BrowserContextImpl",
        "com.microsoft.playwright.impl.PageImpl",
        "com.microsoft.playwright.impl.FrameImpl",
        "com.microsoft.playwright.impl.ElementHandleImpl",
        "com.microsoft.playwright.impl.RouteImpl",
        "com.microsoft.playwright.impl.RequestImpl",
        "com.microsoft.playwright.impl.ResponseImpl",
        "com.microsoft.playwright.impl.WorkerImpl",
        "com.microsoft.playwright.impl.JSHandleImpl",
        "com.microsoft.playwright.impl.ConsoleMessageImpl"
    }
)
public class PlaywrightReflectionConfig {
    // Container for @RegisterForReflection
}
