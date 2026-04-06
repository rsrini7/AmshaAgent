package com.arcclaw.tool.playwright;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotScale;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Base64;
import java.util.regex.Pattern;

@ApplicationScoped
public class PlaywrightPageRunner {

    private static final Logger log = Logger.getLogger(PlaywrightPageRunner.class);

    private static final Pattern SAFE_URL_PATTERN =
        Pattern.compile("^https?://.*", Pattern.CASE_INSENSITIVE);

    private static final int MAX_SCREENSHOT_BYTES = 1_048_576; // 1 MB

    /** Max time to wait for NETWORKIDLE after page LOAD — content is already usable. */
    private static final int NETWORK_IDLE_TIMEOUT_MS = 10_000;

    /** Safety timeout for the LOAD fallback to prevent open-ended waits. */
    private static final int LOAD_FALLBACK_TIMEOUT_MS = 5_000;

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

        long t0 = System.currentTimeMillis();
        log.infof("Playwright launching browser (headless=%s, timeout=%dms) for: %s",
                headless, timeoutMs, url);

        try (Playwright playwright = Playwright.create()) {

            long tBrowserStart = System.currentTimeMillis();
            Browser browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(headless)
                // NOTE: setDevtools() was removed in Playwright 1.58.
                // For debug mode use: .setArgs(List.of("--auto-open-devtools-for-tabs"))
            );
            log.debugf("Chromium launched in %dms", System.currentTimeMillis() - tBrowserStart);

            try (BrowserContext context = browser.newContext()) {
                Page page = context.newPage();
                page.setDefaultNavigationTimeout(timeoutMs);

                // Navigate and verify HTTP status — silent 4xx/5xx returns error HTML without this
                long tNav = System.currentTimeMillis();
                Response response = page.navigate(url);
                log.debugf("Navigation completed in %dms (status=%s)",
                        System.currentTimeMillis() - tNav,
                        response != null ? String.valueOf(response.status()) : "null");

                if (response != null && !response.ok()) {
                    throw new PlaywrightException(
                        "Navigation returned HTTP " + response.status() +
                        " " + response.statusText() + " for: " + url);
                }

                // Wait for network idle with SHORT timeout — content is already usable at LOAD.
                // Sites with persistent polling (analytics, WebSockets) will always time out here;
                // that's fine — we degrade gracefully.
                try {
                    page.waitForLoadState(LoadState.NETWORKIDLE,
                        new Page.WaitForLoadStateOptions().setTimeout(NETWORK_IDLE_TIMEOUT_MS));
                    log.debugf("NETWORKIDLE reached for: %s", url);
                } catch (TimeoutError e) {
                    log.debugf("NETWORKIDLE timeout (%dms) for %s — falling back to LOAD state",
                            NETWORK_IDLE_TIMEOUT_MS, url);
                    // Safety timeout on fallback — LOAD should already be achieved (navigate waits for it),
                    // but we add a bounded wait to prevent any open-ended blocking.
                    try {
                        page.waitForLoadState(LoadState.LOAD,
                            new Page.WaitForLoadStateOptions().setTimeout(LOAD_FALLBACK_TIMEOUT_MS));
                    } catch (TimeoutError loadTimeout) {
                        log.warnf("LOAD fallback also timed out for %s — proceeding with partial content", url);
                    }
                }

                String result = switch (action) {
                    case "screenshot" -> captureScreenshot(page);
                    case "navigate"   -> normalizeWhitespace(page.content());
                    default           -> extractText(page, selector); // extract_text + fallback
                };

                log.infof("Playwright completed action=%s for %s in %dms (result=%d chars)",
                        action, url, System.currentTimeMillis() - t0, result.length());
                return result;
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
