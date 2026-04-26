package com.arcclaw.tool.playwright;

import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotScale;
import com.microsoft.playwright.options.WaitForSelectorState;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class PlaywrightPageRunner {

    private static final Logger log = Logger.getLogger(PlaywrightPageRunner.class);

    @ConfigProperty(name = "arcclaw.tools.playwright.min-content-chars", defaultValue = "200")
    int minContentChars;

    @ConfigProperty(name = "arcclaw.tools.playwright.min-filter-chars", defaultValue = "15")
    int minFilterChars;

    @ConfigProperty(name = "arcclaw.tools.playwright.selector-wait-ms", defaultValue = "2000")
    int selectorWaitMs;

    private static final Pattern SAFE_URL_PATTERN =
        Pattern.compile("^https?://.*", Pattern.CASE_INSENSITIVE);

    private static final int MAX_SCREENSHOT_BYTES = 1_048_576; // 1 MB

    /**
     * NETWORKIDLE budget = 30% of caller timeoutMs.
     * Keeps NETWORKIDLE from eating the entire budget on JS-heavy SPAs.
     * Example: timeoutMs=30000 → 9000ms NETWORKIDLE, 4500ms LOAD fallback.
     */
    private static final double NETWORK_IDLE_RATIO = 0.30;

    /**
     * LOAD fallback budget = 15% of caller timeoutMs.
     * Applied only when NETWORKIDLE times out.
     */
    private static final double LOAD_FALLBACK_RATIO = 0.15;

    /**
     * Extra buffer added to the caller-supplied timeoutMs when waiting for the
     * playwrightThread future - gives Playwright time to throw its own timeout
     * before the outer Future.get() fires.
     */
    private static final long RUN_FUTURE_BUFFER_MS = 5_000L;

    @ConfigProperty(name = "arcclaw.tools.playwright.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "arcclaw.tools.playwright.headless", defaultValue = "true")
    boolean headless;

    // All Playwright objects MUST be accessed exclusively on playwrightThread.
    private Playwright playwright;
    private Browser browser;

    /**
     * Single dedicated thread that owns all Playwright work.
     * Playwright Java requires every call to happen on the thread that created
     * the Playwright instance.
     */
    private final ExecutorService playwrightThread =
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "playwright-worker");
            t.setDaemon(true);
            return t;
        });

    /**
     * Completes when init finishes (successfully or with an error).
     * Callers fast-path via isDone() check in awaitReady().
     */
    private final CompletableFuture<Void> initFuture = new CompletableFuture<>();

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isInitialized() {
        return initFuture.isDone() && !initFuture.isCompletedExceptionally() && browser != null;
    }

    void onStart(@Observes StartupEvent ev) {
        if (!enabled) {
            log.info("Playwright is disabled - skipping browser warmup.");
            initFuture.complete(null);
            return;
        }

        playwrightThread.submit(() -> {
            try {
                long t0 = System.currentTimeMillis();
                Map<String, String> env = resolvePlaywrightEnv();
                playwright = Playwright.create(new Playwright.CreateOptions().setEnv(env));
                browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(headless)
                );
                log.infof("Playwright Chromium ready in %dms (headless=%s)",
                        System.currentTimeMillis() - t0, headless);
                initFuture.complete(null);
            } catch (Exception e) {
                handleInitFailure(e);
                initFuture.completeExceptionally(e);
            }
        });
    }

    @PreDestroy
    void destroy() {
        playwrightThread.submit(() -> {
            closeBrowserQuietly(browser, "destroy");
            browser = null;
            if (playwright != null) {
                try { playwright.close(); } catch (Exception ignored) {}
                playwright = null;
            }
        });
        playwrightThread.shutdown();
        try {
            if (!playwrightThread.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Playwright worker thread did not terminate cleanly within 10s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Scheduled(every = "6h", delayed = "PT5M")
    void refreshBrowser() {
        if (!enabled || !initFuture.isDone() || initFuture.isCompletedExceptionally()) return;
        playwrightThread.submit(() -> {
            log.info("Playwright browser refresh");
            closeBrowserQuietly(browser, "refresh");
            browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(headless)
            );
            log.info("Playwright browser refreshed successfully");
        });
    }

    /**
     * Executes a Playwright action against the given URL.
     *
     * timeoutMs is the TOTAL budget for the entire page interaction:
     *   - page.navigate()           gets the full timeoutMs
     *   - waitForLoadState(IDLE)    gets 30% of timeoutMs
     *   - waitForLoadState(LOAD)    gets 15% of timeoutMs (fallback only)
     *   - Future.get() outer cap    gets timeoutMs + RUN_FUTURE_BUFFER_MS (5s)
     *
     * This ensures the method always returns within ~timeoutMs + 5s regardless
     * of whether the site reaches NETWORKIDLE or not.
     */
    public String run(String url, String action, String selector, int timeoutMs) {
        log.infof("[PlaywrightPageRunner] run() ENTER enabled=%s initDone=%s initFailed=%s browser=%s",
        enabled, initFuture.isDone(), initFuture.isCompletedExceptionally(),
        browser != null ? "OK" : "NULL");

        awaitReady();
        if (!SAFE_URL_PATTERN.matcher(url).matches()) {
            throw new IllegalArgumentException("Unsafe URL rejected: " + url);
        }
        try {
            return playwrightThread
                .submit(() -> doRun(url, action, selector, timeoutMs))
                .get(timeoutMs + RUN_FUTURE_BUFFER_MS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof PlaywrightException pe) throw pe;
            if (cause instanceof IllegalArgumentException ia) throw ia;
            throw new PlaywrightException("Playwright execution failed: " +
                (cause != null ? cause.getMessage() : e.getMessage()));
        } catch (TimeoutException e) {
            throw new PlaywrightException(
                "Playwright run timed out after " + (timeoutMs + RUN_FUTURE_BUFFER_MS) + "ms for: " + url);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlaywrightException("Playwright run interrupted for: " + url);
        }
    }

    private String doRun(String url, String action, String selector, int timeoutMs) {
        if (browser == null) {
            throw new PlaywrightException("Playwright is not correctly initialized. " +
                "Check if Chromium browser binary is installed properly.");
        }

        // Derive proportional sub-budgets from the caller's total timeoutMs.
        // This prevents NETWORKIDLE from eating the entire budget on JS-heavy SPAs.
        int networkIdleTimeoutMs = (int) (timeoutMs * NETWORK_IDLE_RATIO);
        int loadFallbackTimeoutMs = (int) (timeoutMs * LOAD_FALLBACK_RATIO);

        long t0 = System.currentTimeMillis();
        log.infof("[Playwright] Starting action=%s url=%s (nav=%dms idle=%dms load=%dms)",
                action, url, timeoutMs, networkIdleTimeoutMs, loadFallbackTimeoutMs);

        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            page.setDefaultNavigationTimeout(timeoutMs);
            page.setDefaultTimeout(timeoutMs);

            // Navigate and verify HTTP status
            long tNav = System.currentTimeMillis();
            Response response = page.navigate(url);
            log.debugf("[Playwright] navigate() completed in %dms (status=%s)",
                    System.currentTimeMillis() - tNav,
                    response != null ? String.valueOf(response.status()) : "null");

            if (response != null && !response.ok()) {
                throw new PlaywrightException(
                    "Navigation returned HTTP " + response.status() +
                    " " + response.statusText() + " for: " + url);
            }

            // Wait for NETWORKIDLE using proportional budget.
            // JS-heavy SPAs (quarkus.io, spring.io) rarely reach NETWORKIDLE -
            // we degrade to LOAD fallback quickly rather than burning the full budget.
            waitForPageReady(page, url, networkIdleTimeoutMs, loadFallbackTimeoutMs);

            // --- Human-Like Interaction to trigger lazy-loading ---
            try {
                page.evaluate("window.scrollBy(0, window.innerHeight * 0.5)");
                Thread.sleep(500); 
                page.evaluate("window.scrollBy(0, window.innerHeight)");
                Thread.sleep(500);
            } catch (Exception ignored) {}

            String result = switch (action) {
                case "screenshot" -> captureScreenshot(page);
                case "navigate"   -> normalizeWhitespace(page.content());
                default           -> extractText(page, selector);
            };

            log.infof("[Playwright] Completed action=%s url=%s in %dms (result=%d chars)",
                    action, url, System.currentTimeMillis() - t0, result.length());
            return result;
        }
    }

    /**
     * Waits for page readiness using a two-stage strategy:
     * 1. Try NETWORKIDLE with networkIdleTimeoutMs budget.
     * 2. If NETWORKIDLE times out (common on JS-heavy SPAs), fall back to LOAD with loadFallbackTimeoutMs.
     * 3. If LOAD also times out, warn and proceed - content is likely usable after navigate() returned.
     *
     * Previously used hardcoded 10s/5s values regardless of caller's timeoutMs, causing
     * total doRun() time to exceed caller budget on slow sites.
     */
    private void waitForPageReady(Page page, String url, int networkIdleTimeoutMs, int loadFallbackTimeoutMs) {
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(networkIdleTimeoutMs));
            log.debugf("[Playwright] NETWORKIDLE reached for: %s", url);
        } catch (TimeoutError e) {
            log.debugf("[Playwright] NETWORKIDLE timeout (%dms) for %s - falling back to LOAD",
                    networkIdleTimeoutMs, url);
            try {
                page.waitForLoadState(LoadState.LOAD, new Page.WaitForLoadStateOptions().setTimeout(loadFallbackTimeoutMs));
            } catch (TimeoutError ignored) {
                log.warnf("[Playwright] LOAD fallback also timed out (%dms) for %s - proceeding with partial content",
                        loadFallbackTimeoutMs, url);
            }
        }
    }

    private String extractText(Page page, String llmSelector) {
        // 1. Try LLM-provided selector
        if (llmSelector != null && !llmSelector.isBlank()) {
            try {
                Locator loc = page.locator(llmSelector);
                loc.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(selectorWaitMs));
                String result = loc.allInnerTexts().stream()
                    .map(t -> normalizeWhitespace(t.trim()))
                    .filter(s -> s.length() > minFilterChars)
                    .collect(Collectors.joining("\n\n---\n\n"));
                if (result.length() > minContentChars) return result;
                log.warnf("[Playwright] Selector '%s' returned only %d chars (min=%d) - engaging fallback chain",
                        llmSelector, result.length(), minContentChars);
            } catch (Exception e) {
                log.warnf("[Playwright] Selector '%s' failed or timed out - engaging fallback chain", llmSelector);
            }
        }

        // 2. Greedy Semantic Fallback
        String[] fallbacks = {
            "article", "main", "[role='main']", ".content", 
            "[class*='blog'] .post, .post, .card",
            "[class*='blog'] h2, [class*='post'] h2, article h2",
            "body"
        };
        String best = "";
        for (String sel : fallbacks) {
            try {
                // If it's the same as the already-failed llmSelector, skip
                if (sel.equalsIgnoreCase(llmSelector)) continue;

                page.waitForSelector(sel, new Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(selectorWaitMs));
                String current = page.locator(sel).allInnerTexts().stream()
                    .map(t -> normalizeWhitespace(t.trim()))
                    .filter(s -> s.length() > minFilterChars)
                    .collect(Collectors.joining("\n\n---\n\n"));
                
                if (current.length() > best.length()) {
                    best = current;
                    log.debugf("[Playwright] Found %d chars using fallback selector '%s'", best.length(), sel);
                }
                if (best.length() > 3000) break; // found a good large chunk
            } catch (Exception ignored) {}
        }

        if (!best.isBlank() && best.length() > minContentChars) return best;

        try {
            return normalizeWhitespace(page.locator("body").innerText(
                new Locator.InnerTextOptions().setTimeout(10000)));
        } catch (Exception e) {
            return "Error: Could not extract content.";
        }
    }

    private String captureScreenshot(Page page) {
        byte[] png = page.screenshot(new Page.ScreenshotOptions().setFullPage(false));
        if (png.length > MAX_SCREENSHOT_BYTES) {
            png = page.screenshot(new Page.ScreenshotOptions().setFullPage(false).setScale(ScreenshotScale.CSS));
        }
        return Base64.getEncoder().encodeToString(png);
    }

    /**
     * Fast-path: if initFuture is already done and successful, return immediately.
     * This avoids Future.get() overhead on every hot-path invocation after startup.
     */
    private void awaitReady() {
        if (!enabled || (initFuture.isDone() && !initFuture.isCompletedExceptionally())) return;
        try {
            initFuture.get(60, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new IllegalStateException("Playwright init failed: " +
                (cause != null ? cause.getMessage() : e.getMessage()), e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Playwright init timed out after 60s", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Playwright init interrupted", e);
        }
    }

    private Map<String, String> resolvePlaywrightEnv() {
        Map<String, String> env = new HashMap<>(System.getenv());
        String browsersPath = System.getProperty("PLAYWRIGHT_BROWSERS_PATH", System.getenv("PLAYWRIGHT_BROWSERS_PATH"));
        if (browsersPath != null && !browsersPath.isBlank()) {
            String resolvedPath = Path.of(browsersPath).toAbsolutePath().normalize().toString();
            env.put("PLAYWRIGHT_BROWSERS_PATH", resolvedPath);
            System.setProperty("PLAYWRIGHT_BROWSERS_PATH", resolvedPath);
            log.infof("[Playwright] Initializing with BROWSERS_PATH=%s", resolvedPath);
        } else {
            log.info("[Playwright] Initializing with default browser path");
        }

        String skipDownload = System.getProperty("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD",
                System.getenv("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD"));
        if (skipDownload != null && !skipDownload.isBlank()) {
            env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", skipDownload);
        }

        return env;
    }

    private void handleInitFailure(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (msg.contains("Executable doesn't exist")) {
            String summary = msg.split("\n")[0].trim();
            log.warnf("[Playwright] Chromium binary not found - initialization skipped. " +
                     "(Run 'npx playwright install chromium'). Details: %s", summary);
        } else {
            log.errorf(e, "[Playwright] Initialization failed: %s", msg);
        }
        closeBrowserQuietly(browser, "init-failure");
        browser = null;
        if (playwright != null) {
            try { playwright.close(); } catch (Exception ignored) {}
            playwright = null;
        }
    }

    private void closeBrowserQuietly(Browser browserRef, String phase) {
        if (browserRef == null) return;
        try {
            browserRef.close();
        } catch (Exception e) {
            if (isBenignCloseError(e)) {
                log.debugf("[Playwright] Browser close skipped benign error during %s: %s",
                        phase, safeMessage(e));
            } else {
                log.warnf("[Playwright] Browser close failed during %s: %s", phase, safeMessage(e));
            }
        }
    }

    private boolean isBenignCloseError(Throwable t) {
        String msg = safeMessage(t).toLowerCase();
        return msg.contains("stream closed")
            || msg.contains("pipe closed")
            || msg.contains("pipe is being closed")
            || msg.contains("target page, context or browser has been closed");
    }

    private String normalizeWhitespace(String text) {
        return text == null ? "" : text.replaceAll("\n{3,}", "\n\n").trim();
    }

    private String safeMessage(Throwable t) {
        String msg = t.getMessage();
        return (msg == null || msg.isBlank()) ? t.getClass().getSimpleName() : msg;
    }
}
