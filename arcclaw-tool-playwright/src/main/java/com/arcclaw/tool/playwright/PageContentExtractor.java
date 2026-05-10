package com.arcclaw.tool.playwright;

import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class PageContentExtractor {

    private static final Logger log = Logger.getLogger(PageContentExtractor.class);

    @ConfigProperty(name = "arcclaw.tools.playwright.min-content-chars", defaultValue = "200")
    int minContentChars;

    @ConfigProperty(name = "arcclaw.tools.playwright.min-filter-chars", defaultValue = "15")
    int minFilterChars;

    @ConfigProperty(name = "arcclaw.tools.playwright.selector-wait-ms", defaultValue = "2000")
    int selectorWaitMs;

    private static final Pattern NEWLINE_CONSOLIDATION_PATTERN = Pattern.compile("\n{3,}");

    public String extractText(Page page, String llmSelector) {
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
                if (sel.equalsIgnoreCase(llmSelector)) continue;

                // The page is already loaded, so we only wait a tiny bit (100ms) or just check immediately
                // to avoid 14+ seconds of cumulative timeout delays.
                Locator loc = page.locator(sel);
                if (loc.count() == 0) continue;

                String current = loc.allInnerTexts().stream()
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

    public String normalizeWhitespace(String text) {
        return text == null ? "" : NEWLINE_CONSOLIDATION_PATTERN.matcher(text).replaceAll("\n\n").trim();
    }
}
