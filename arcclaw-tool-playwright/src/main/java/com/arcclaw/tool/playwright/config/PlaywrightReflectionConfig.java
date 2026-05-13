package com.arcclaw.tool.playwright.config;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(classNames = {
    "com.microsoft.playwright.APIRequestContext$DisposeOptions",
    "com.microsoft.playwright.BrowserContext$CloseOptions",
    "com.microsoft.playwright.Page$NavigateOptions",
    "com.microsoft.playwright.Page$ScreenshotOptions",
    "com.microsoft.playwright.options.WaitUntilState",
    "com.microsoft.playwright.options.LoadState"
})
public class PlaywrightReflectionConfig {
    // Container for @RegisterForReflection to ensure Playwright internal options 
    // classes can be instantiated via reflection in GraalVM native image.
}
