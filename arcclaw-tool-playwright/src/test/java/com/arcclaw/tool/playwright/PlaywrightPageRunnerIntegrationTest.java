package com.arcclaw.tool.playwright;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.microsoft.playwright.PlaywrightException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@QuarkusTest
@Tag("integration")
class PlaywrightPageRunnerIntegrationTest {

    @Inject
    PlaywrightPageRunner runner;

    @BeforeEach
    void checkEnabled() {
        Assumptions.assumeTrue(
            runner.enabled,
            "Skipping: Playwright tool is disabled in configuration"
        );
        Assumptions.assumeTrue(
            runner.isInitialized(),
            "Skipping: Playwright runner failed to initialize (check Chromium binaries)"
        );
    }

    @Test
    void extractText_returnsPageContent() {
        String result = runner.run("https://example.com", "extract_text", null,  30000);
        Assertions.assertTrue(result != null && result.toLowerCase().contains("example domain"));
    }

    @Test
    void extractText_withSelectorH1_returnsHeading() {
        String result = runner.run("https://example.com", "extract_text", "h1",  30000);
        Assertions.assertTrue(result != null && result.toLowerCase().contains("example domain"));
    }

    @Test
    void screenshot_returnsBase64Png() {
        String result = runner.run("https://example.com", "screenshot", null,  30000);
        // Base64 PNG magic bytes: iVBORw
        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.startsWith("iVBORw"));
    }

    @Test
    void navigate_http404_throwsPlaywrightException() {
        assertThrows(PlaywrightException.class, () ->
            runner.run("https://httpstat.us/404", "extract_text", null,  30000));
    }
}
