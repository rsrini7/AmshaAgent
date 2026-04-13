package com.arcclaw.tool.playwright;

import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotScale;
import com.microsoft.playwright.options.WaitForSelectorState;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@Startup
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

    @ConfigProperty(name = "arcclaw.tools.playwright.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "arcclaw.tools.playwright.headless", defaultValue = "true")
    boolean headless;

    private Playwright playwright;
    private Browser browser;

    /**
     * Guards @Scheduled refreshBrowser against the scheduler-vs-startup race in native mode.
     * The scheduler can fire before @PostConstruct completes — this flag plus delayed="PT5M"
     * together prevent refreshBrowser from acting before init() has finished.
     */
    private volatile boolean startupComplete = false;

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isInitialized() {
        return browser != null;
    }

    @PostConstruct
    void init() {
        if (!enabled) {
            log.info("Playwright is disabled — skipping browser warmup.");
            startupComplete = true;
            return;
        }

        try {
            long t0 = System.currentTimeMillis();

            // Resolve BROWSERS_PATH and SKIP_BROWSER_DOWNLOAD from SysProp or Env
            Map<String, String> env = new HashMap<>(System.getenv());
            
            String browsersPath = System.getProperty("PLAYWRIGHT_BROWSERS_PATH", 
                    System.getenv("PLAYWRIGHT_BROWSERS_PATH"));
            
            if (browsersPath != null && !browsersPath.isBlank()) {
                // Ensure the path is absolute for consistency across launch environments (Fix for Windows Binary)
                Path p = Path.of(browsersPath).toAbsolutePath().normalize();
                String resolvedPath = p.toString();
                env.put("PLAYWRIGHT_BROWSERS_PATH", resolvedPath);
                // Sync back to system property for visibility in logs/other components
                System.setProperty("PLAYWRIGHT_BROWSERS_PATH", resolvedPath);
                log.infof("Initializing Playwright with BROWSERS_PATH=%s", resolvedPath);
            } else {
                log.info("Initializing Playwright with default browser path");
            }

            String skipDownload = System.getProperty("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD",
                    System.getenv("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD"));
            if (skipDownload != null && !skipDownload.isBlank()) {
                env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", skipDownload);
            }

            playwright = Playwright.create(new Playwright.CreateOptions().setEnv(env));
            browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(headless)
            );
            log.infof("Playwright Chromium cached in %dms (headless=%s)",
                    System.currentTimeMillis() - t0, headless);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (playwright != null) {
                    try { playwright.close(); } catch (Exception ignored) {}
                }
            }, "playwright-shutdown"));

        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (msg.contains("Executable doesn't exist")) {
                // Concise warning: take only the first line to avoid giant Node/driver stack traces
                String summary = msg.split("\n")[0].trim();
                log.warnf("Playwright Chromium binary not found. Initialization skipped. " +
                         "(Run 'npx playwright install chromium' or check path). Details: %s", summary);
            } else {
                log.errorf(e, "Playwright initialization failed: %s", msg);
            }
            destroy();
        } finally {
            startupComplete = true;
        }
    }

    @PreDestroy
    void destroy() {
        // Close browser before playwright to avoid orphaned subprocesses
        closeBrowserQuietly(browser, "destroy");
        browser = null;
        if (playwright != null) {
            try {
                playwright.close();
            } catch (Exception e) {
                log.debugf("Playwright close during destroy: %s", safeMessage(e));
            }
            playwright = null;
        }
    }

    /**
     * Periodic browser refresh to avoid stale state accumulating over long uptimes.
     * delayed="PT5M" provides the primary guard against the scheduler-vs-startup race
     * in native mode. startupComplete is the secondary guard.
     */
    @Scheduled(every = "6h", delayed = "PT5M")
    void refreshBrowser() {
        if (!startupComplete || !enabled || playwright == null) return;
        log.info("Playwright browser refresh");
        closeBrowserQuietly(browser, "refresh");
        browser = playwright.chromium().launch(
            new BrowserType.LaunchOptions().setHeadless(headless)
        );
    }

    private void closeBrowserQuietly(Browser browserRef, String phase) {
        if (browserRef == null) return;
        try {
            browserRef.close();
        } catch (Exception e) {
            if (isBenignCloseError(e)) {
                log.debugf("Playwright browser close skipped benign error during %s: %s", phase, safeMessage(e));
            } else {
                log.warnf("Playwright browser close failed during %s: %s", phase, safeMessage(e));
            }
        }
    }

    private boolean isBenignCloseError(Throwable t) {
        String msg = safeMessage(t).toLowerCase();
        return msg.contains("stream closed")
            || msg.contains("pipe closed")
            || msg.contains("target page, context or browser has been closed");
    }

    private String safeMessage(Throwable t) {
        String msg = t.getMessage();
        return (msg == null || msg.isBlank()) ? t.getClass().getSimpleName() : msg;
    }

    /**
     * Reuses the cached Chromium browser context, navigates to url, returns result.
     *
     * @param url       target URL (must match SAFE_URL_PATTERN — caller pre-validates)
     * @param action    "navigate" | "extract_text" | "screenshot"
     * @param selector  optional CSS selector to scope extraction (null = full body)
     * @param timeoutMs navigation timeout in milliseconds
     * @return result string: visible text, raw HTML, or base64 PNG
     * @throws PlaywrightException      on navigation or execution failure
     * @throws IllegalArgumentException on unsafe URL
     */
    public synchronized String run(String url, String action, String selector, int timeoutMs) {
        if (!enabled || browser == null) {
            throw new PlaywrightException("Playwright is not correctly initialized. " +
                "Check if Chromium browser binary is installed properly.");
        }

        if (!SAFE_URL_PATTERN.matcher(url).matches()) {
            throw new IllegalArgumentException("Unsafe URL rejected: " + url);
        }

        long t0 = System.currentTimeMillis();
        log.infof("Playwright reusing cached browser for: %s", url);

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
                default           -> extractText(page, selector);
            };

            log.infof("Playwright completed action=%s for %s in %dms (result=%d chars)",
                    action, url, System.currentTimeMillis() - t0, result.length());
            return result;
        }
    }

    private String extractText(Page page, String llmSelector) {
        // 1. Try LLM-provided selector — wait for visibility on SPAs
        if (llmSelector != null && !llmSelector.isBlank()) {
            try {
                Locator loc = page.locator(llmSelector);
                loc.first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(3000));                    // ← RESTORE waitFor
                String result = loc.allInnerTexts().stream()
                    .map(t -> normalizeWhitespace(t.trim())) // ← RESTORE normalizeWhitespace
                    .filter(s -> s.length() > 10)
                    .collect(Collectors.joining("\n"));
                if (result.length() > 50) return result;
                log.warnf("LLM selector '%s' returned only %d chars — engaging fallback chain",
                        llmSelector, result.length());
            } catch (Exception e) {
                log.warnf("LLM selector '%s' failed or timed out — engaging fallback chain", llmSelector);
            }
        }

        // 2. Semantic fallback chain
        String[] fallbackSelectors = {
            "article h1, article h2, article h3",
            "main h1, main h2, main h3",
            "[class*='title'], [class*='heading'], [class*='post-title']",
            "h1, h2, h3",
            "main p",
            "body"
        };
        for (String sel : fallbackSelectors) {
            try {
                String result = page.locator(sel).allInnerTexts().stream()
                    .map(t -> normalizeWhitespace(t.trim()))  // ← normalizeWhitespace here too
                    .filter(s -> s.length() > 15)
                    .distinct()
                    .collect(Collectors.joining("\n"));
                if (result.length() > 50) {
                    log.infof("Fallback selector '%s' yielded %d chars", sel, result.length());
                    return result;
                }
            } catch (Exception ignored) {}
        }

        // 3. Nuclear fallback — 10s timeout cap to prevent hangs  ← RESTORE timeout
        try {
            return normalizeWhitespace(page.locator("body").innerText(
                new Locator.InnerTextOptions().setTimeout(10000)));
        } catch (Exception e) {
            return "Error: Could not extract text from page body.";
        }
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
