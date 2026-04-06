package com.arcclaw.tool.playwright;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotScale;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Base64;
import java.util.regex.Pattern;

@ApplicationScoped
public class PlaywrightPageRunner {

    private static final Pattern SAFE_URL_PATTERN =
        Pattern.compile("^https?://.*", Pattern.CASE_INSENSITIVE);

    private static final int MAX_SCREENSHOT_BYTES = 1_048_576; // 1 MB

    /**
     * Launches a fresh Chromium browser context, navigates to url, returns result.
     * Each call creates and destroys its own Playwright + Browser instance — no shared state.
     *
     * @param url       target URL (must match SAFE_URL_PATTERN — caller pre-validates)
     * @param action    "navigate" | "extract_text" | "screenshot"
     * @param selector  optional CSS selector to scope extraction (null = full body)
     * @param headless  run headless (true in production)
     * @param timeoutMs navigation timeout in milliseconds
     * @return result string: visible text, raw HTML, or base64 PNG
     * @throws PlaywrightException      on navigation or execution failure
     * @throws IllegalArgumentException on unsafe URL
     */
    public String run(String url, String action, String selector,
                      boolean headless, int timeoutMs) {

        if (!SAFE_URL_PATTERN.matcher(url).matches()) {
            throw new IllegalArgumentException("Unsafe URL rejected: " + url);
        }

        try (Playwright playwright = Playwright.create()) {

            Browser browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(headless)
                // NOTE: setDevtools() was removed in Playwright 1.58.
                // For debug mode use: .setArgs(List.of("--auto-open-devtools-for-tabs"))
            );

            try (BrowserContext context = browser.newContext()) {
                Page page = context.newPage();
                page.setDefaultNavigationTimeout(timeoutMs);

                // Navigate and verify HTTP status — silent 4xx/5xx returns error HTML without this
                Response response = page.navigate(url);
                if (response != null && !response.ok()) {
                    throw new PlaywrightException(
                        "Navigation returned HTTP " + response.status() +
                        " " + response.statusText() + " for: " + url);
                }

                // Wait for network idle; degrade to LOAD for sites that poll continuously
                try {
                    page.waitForLoadState(LoadState.NETWORKIDLE,
                        new Page.WaitForLoadStateOptions().setTimeout(timeoutMs));
                } catch (TimeoutError e) {
                    // Site has persistent network activity (analytics, WebSocket, etc.)
                    // Degrade gracefully — page content is still usable at LOAD state
                    page.waitForLoadState(LoadState.LOAD);
                }

                return switch (action) {
                    case "screenshot" -> captureScreenshot(page);
                    case "navigate"   -> normalizeWhitespace(page.content());
                    default           -> extractText(page, selector); // extract_text + fallback
                };
            }
        }
    }

    private String extractText(Page page, String selector) {
        String scope = (selector != null && !selector.isBlank()) ? selector : "body";
        return normalizeWhitespace(page.locator(scope).innerText());
    }

    private String captureScreenshot(Page page) {
        byte[] png = page.screenshot(
            new Page.ScreenshotOptions().setFullPage(false));

        // Re-shoot at CSS scale if over 1 MB (reduces size on hi-DPI displays)
        if (png.length > MAX_SCREENSHOT_BYTES) {
            png = page.screenshot(new Page.ScreenshotOptions()
                .setFullPage(false)
                .setScale(ScreenshotScale.CSS));
        }
        return Base64.getEncoder().encodeToString(png);
    }

    /** Collapses 3+ consecutive newlines to 2; trims whitespace. Never log result. */
    private String normalizeWhitespace(String text) {
        return text == null ? "" : text.replaceAll("\\n{3,}", "\n\n").trim();
    }
}
