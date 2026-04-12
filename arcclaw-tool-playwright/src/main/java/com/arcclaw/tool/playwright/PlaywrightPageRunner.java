package com.arcclaw.tool.playwright;

import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
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

            // Map system properties from .env (loaded by AppBootstrap) to Playwright's env
            Map<String, String> env = new HashMap<>(System.getenv());
            String browsersPathProp = System.getProperty("PLAYWRIGHT_BROWSERS_PATH");
            String resolvedPath = null;

            if (browsersPathProp != null && !browsersPathProp.isBlank()) {
                // Ensure the path is absolute for consistency across launch environments (Fix for Windows Binary)
                Path p = Path.of(browsersPathProp).toAbsolutePath().normalize();
                resolvedPath = p.toString();
                env.put("PLAYWRIGHT_BROWSERS_PATH", resolvedPath);
                System.setProperty("PLAYWRIGHT_BROWSERS_PATH", resolvedPath);
            }

            String skipDownload = System.getProperty("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD");
            if (skipDownload != null && !skipDownload.isBlank()) {
                env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", skipDownload);
            }

            log.infof("Initializing Playwright with BROWSERS_PATH=%s",
                    resolvedPath != null ? resolvedPath : "default");

            playwright = Playwright.create(new Playwright.CreateOptions().setEnv(env));
            browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(headless)
            );
            log.infof("Playwright Chromium cached in %dms (headless=%s)",
                    System.currentTimeMillis() - t0, headless);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            log.errorf(e, "Playwright initialization failed: %s", msg);
            if (msg.contains("Executable doesn't exist")) {
                log.warn("Playwright Chromium binary not found. Run 'playwright install chromium' " +
                         "or install via npx before launching ArcClaw.");
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

    private String extractText(Page page, String selector) {
        if (selector != null && !selector.isBlank() && !"body".equalsIgnoreCase(selector)) {
            try {
                // Ensure visibility before extraction to avoid immediate empty returns on SPA/Lazy sites.
                Locator loc = page.locator(selector);
                loc.first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(3000));

                List<String> texts = loc.allInnerTexts();
                String joined = texts.stream()
                    .map(t -> normalizeWhitespace(t.strip()))
                    .filter(t -> t.length() > 5)
                    .collect(Collectors.joining("\n"));

                if (joined.length() > 20) return joined;
                log.warnf("LLM selector '%s' returned only %d chars — engaging fallback chain",
                        selector, joined.length());
            } catch (Exception e) {
                log.warnf("LLM selector '%s' failed or timed out — engaging fallback chain", selector);
            }
        }

        // Expanded fallback chain for better discovery on modern blogs/SPAs
        String[] fallbacks = {"h1", "h2", "h3", "article h2", "article h3", ".post-title", ".entry-title"};
        for (String f : fallbacks) {
            try {
                // No waitFor on fallbacks — instant empty list if selector is absent.
                // We only block on the primary LLM-provided selector (already protected above).
                List<String> texts = page.locator(f).allInnerTexts();
                String joined = texts.stream()
                    .map(t -> normalizeWhitespace(t.strip()))
                    .filter(t -> t.length() > 5)
                    .collect(Collectors.joining("\n"));

                if (joined.length() > 20) return joined;
            } catch (Exception ignored) {}
        }

        // Final fallback: Guaranteed full body (10s cap to prevent edge-case hangs)
        try {
            return normalizeWhitespace(page.locator("body").innerText(
                new Locator.InnerTextOptions().setTimeout(10000)));
        } catch (Exception e) {
            return "Error: Could not extract text from body.";
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
