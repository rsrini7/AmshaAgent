package com.arcclaw.tool.playwright;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class PlaywrightPageRunnerIntegrationTest {

    static PlaywrightPageRunner runner;

    @BeforeAll
    static void checkEnvAndInit() {
        String enabled = System.getenv("ARCCLAW_TOOLS_PLAYWRIGHT_ENABLED");
        Assumptions.assumeTrue(
            "true".equalsIgnoreCase(enabled),
            "Skipping: ARCCLAW_TOOLS_PLAYWRIGHT_ENABLED is not set to true"
        );
        runner = new PlaywrightPageRunner();
    }

    @Test
    void extractText_returnsPageContent() {
        String result = runner.run("https://example.com", "extract_text", null, true, 30000);
        Assertions.assertTrue(result != null && result.toLowerCase().contains("example domain"));
    }

    @Test
    void extractText_withSelectorH1_returnsHeading() {
        String result = runner.run("https://example.com", "extract_text", "h1", true, 30000);
        Assertions.assertTrue(result != null && result.toLowerCase().contains("example domain"));
    }

    @Test
    void screenshot_returnsBase64Png() {
        String result = runner.run("https://example.com", "screenshot", null, true, 30000);
        // Base64 PNG magic bytes: iVBORw
        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.startsWith("iVBORw"));
    }
}
