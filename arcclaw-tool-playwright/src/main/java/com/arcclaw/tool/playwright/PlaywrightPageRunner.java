package com.arcclaw.tool.playwright;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class PlaywrightPageRunner {

    private static final Logger log = Logger.getLogger(PlaywrightPageRunner.class);

    @ConfigProperty(name = "arcclaw.tools.playwright.run-future-buffer-ms", defaultValue = "5000")
    long runFutureBufferMs;

    private static final Pattern SAFE_URL_PATTERN =
        Pattern.compile("^https?://.*", Pattern.CASE_INSENSITIVE);

    @ConfigProperty(name = "arcclaw.tools.playwright.enabled", defaultValue = "false")
    boolean configEnabled;

    @ConfigProperty(name = "arcclaw.tools.playwright.headless", defaultValue = "true")
    boolean headless;

    private final NavigationStrategy navigationStrategy;
    private final ScreenshotCapture screenshotCapture;
    private final PageContentExtractor contentExtractor;

    private boolean runtimeEnabled;

    // All Playwright objects MUST be accessed exclusively on playwrightThread.
    private Playwright playwright;
    private Browser browser;

    public PlaywrightPageRunner(
            NavigationStrategy navigationStrategy,
            ScreenshotCapture screenshotCapture,
            PageContentExtractor contentExtractor) {
        this.navigationStrategy = navigationStrategy;
        this.screenshotCapture = screenshotCapture;
        this.contentExtractor = contentExtractor;
    }

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

    @PostConstruct
    void init() {
        this.runtimeEnabled = configEnabled;
    }

    /**
     * Completes when init finishes (successfully or with an error).
     * Callers fast-path via isDone() check in awaitReady().
     */
    private final CompletableFuture<Void> initFuture = new CompletableFuture<>();

    public boolean isEnabled() {
        return runtimeEnabled;
    }

    public boolean isInitialized() {
        return initFuture.isDone() && !initFuture.isCompletedExceptionally() && browser != null;
    }

    void onStart(@Observes StartupEvent ev) {
        if (!runtimeEnabled) {
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
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (msg.contains("Executable doesn't exist") || msg.contains("Chromium binary not found") || msg.contains("Failed to create Playwright")) {
                    try {
                        String bPath = System.getProperty("PLAYWRIGHT_BROWSERS_PATH", System.getenv("PLAYWRIGHT_BROWSERS_PATH"));
                        if (bPath != null && !bPath.isBlank()) {
                            Path resolvedPath = Path.of(bPath).toAbsolutePath().normalize();
                            try {
                                Class<?> clazz = Class.forName("com.arcclaw.app.config.PlaywrightBrowserInstaller");
                                Method m = clazz.getMethod("installChromium", Path.class);
                                m.invoke(null, resolvedPath);
                            } catch (Exception ex) {
                                log.warnf("[PlaywrightPageRunner] Could not invoke PlaywrightBrowserInstaller: %s", ex.getMessage());
                            }
                        }
                            
                            long t0 = System.currentTimeMillis();
                            Map<String, String> env = resolvePlaywrightEnv();
                            if (playwright != null) {
                                try { playwright.close(); } catch (Exception ignored) {}
                            }
                            playwright = Playwright.create(new Playwright.CreateOptions().setEnv(env));
                            browser = playwright.chromium().launch(
                                new BrowserType.LaunchOptions().setHeadless(headless)
                            );
                            log.infof("Playwright Chromium ready after auto-install in %dms (headless=%s)",
                                    System.currentTimeMillis() - t0, headless);
                            initFuture.complete(null);
                            return;
                    } catch (Exception ex) {
                        log.error("Programmatic installation/launch retry failed", ex);
                    }
                }
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
            closePlaywrightQuietly(playwright, "destroy");
            playwright = null;
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
        if (!runtimeEnabled || !initFuture.isDone() || initFuture.isCompletedExceptionally()) return;
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
     */
    public String run(String url, String action, String selector, int timeoutMs) {
        log.infof("[PlaywrightPageRunner] run() ENTER enabled=%s initDone=%s initFailed=%s browser=%s",
        runtimeEnabled, initFuture.isDone(), initFuture.isCompletedExceptionally(),
        browser != null ? "OK" : "NULL");

        awaitReady();
        if (!SAFE_URL_PATTERN.matcher(url).matches()) {
            throw new IllegalArgumentException("Unsafe URL rejected: " + url);
        }
        try {
            return playwrightThread
                .submit(() -> doRun(url, action, selector, timeoutMs))
                .get(timeoutMs + runFutureBufferMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof PlaywrightException pe) throw pe;
            if (cause instanceof IllegalArgumentException ia) throw ia;
            throw new PlaywrightException("Playwright execution failed: " +
                (cause != null ? cause.getMessage() : e.getMessage()));
        } catch (TimeoutException e) {
            throw new PlaywrightException(
                "Playwright run timed out after " + (timeoutMs + runFutureBufferMs) + "ms for: " + url);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlaywrightException("Playwright run interrupted for: " + url);
        }
    }

    private String doRun(String url, String action, String selector, int timeoutMs) {
        if (browser == null) {
            throw new PlaywrightException("Playwright browser (Chromium) is not initialized.");
        }

        long t0 = System.currentTimeMillis();
        log.infof("[Playwright] Starting action=%s url=%s (timeout=%dms)", action, url, timeoutMs);

        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            page.setDefaultNavigationTimeout(timeoutMs);
            page.setDefaultTimeout(timeoutMs);

            Response response = page.navigate(url);
            if (response != null && !response.ok()) {
                throw new PlaywrightException(
                    "Navigation returned HTTP " + response.status() +
                    " " + response.statusText() + " for: " + url);
            }

            navigationStrategy.waitForPageReady(page, url, timeoutMs);

            // --- Human-Like Interaction to trigger lazy-loading ---
            try {
                page.evaluate("window.scrollBy(0, window.innerHeight * 0.5)");
                Thread.sleep(500); 
                page.evaluate("window.scrollBy(0, window.innerHeight)");
                Thread.sleep(500);
            } catch (Exception e) {
                log.debugf("Failed human-like interaction for %s: %s", url, ExceptionUtils.getRootCauseMessage(e));
            }

            String result = switch (action) {
                case "screenshot" -> screenshotCapture.captureScreenshot(page);
                case "navigate"   -> contentExtractor.normalizeWhitespace(page.content());
                default           -> contentExtractor.extractText(page, selector);
            };

            log.infof("[Playwright] Completed action=%s url=%s in %dms (result=%d chars)",
                    action, url, System.currentTimeMillis() - t0, result.length());
            return result;
        }
    }

    /**
     * Fast-path: if initFuture is already done and successful, return immediately.
     */
    private void awaitReady() {
        if (!runtimeEnabled || (initFuture.isDone() && !initFuture.isCompletedExceptionally())) return;
        try {
            initFuture.get(60, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String msg = (cause != null ? cause.getMessage() : e.getMessage());
            if (msg != null && msg.contains("Executable doesn't exist")) {
                throw new PlaywrightException("Playwright Chromium binary not found.");
            }
            throw new PlaywrightException("Playwright initialization failed: " + msg);
        } catch (TimeoutException e) {
            throw new PlaywrightException("Playwright initialization timed out after 60s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlaywrightException("Playwright initialization interrupted");
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
        }
        return env;
    }

    private void handleInitFailure(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        log.errorf(e, "[Playwright] Initialization failed: %s", msg);
        closeBrowserQuietly(browser, "init-failure");
        browser = null;
        closePlaywrightQuietly(playwright, "init-failure");
        playwright = null;
    }

    private void closeBrowserQuietly(Browser browserRef, String phase) {
        if (browserRef == null) return;
        try {
            browserRef.close();
        } catch (Throwable t) {
            log.debugf("[Playwright] Browser close failed during %s: %s", phase, safeMessage(t));
        }
    }

    private void closePlaywrightQuietly(Playwright playwrightRef, String phase) {
        if (playwrightRef == null) return;
        try {
            playwrightRef.close();
        } catch (Throwable t) {
            log.debugf("[Playwright] Playwright close failed during %s: %s", phase, safeMessage(t));
        }
    }

    private String safeMessage(Throwable t) {
        String msg = t.getMessage();
        return (msg == null || msg.isBlank()) ? t.getClass().getSimpleName() : msg;
    }
}
