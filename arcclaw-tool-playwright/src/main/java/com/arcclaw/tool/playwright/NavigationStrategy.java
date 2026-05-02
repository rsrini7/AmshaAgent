package com.arcclaw.tool.playwright;

import jakarta.enterprise.context.ApplicationScoped;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.LoadState;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class NavigationStrategy {

    private static final Logger log = Logger.getLogger(NavigationStrategy.class);

    @ConfigProperty(name = "arcclaw.tools.playwright.network-idle-ratio", defaultValue = "0.30")
    double networkIdleRatio;

    private static final double LOAD_FALLBACK_RATIO = 0.15;

    public void waitForPageReady(Page page, String url, int timeoutMs) {
        int networkIdleTimeoutMs = (int) (timeoutMs * networkIdleRatio);
        int loadFallbackTimeoutMs = (int) (timeoutMs * LOAD_FALLBACK_RATIO);

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
}
