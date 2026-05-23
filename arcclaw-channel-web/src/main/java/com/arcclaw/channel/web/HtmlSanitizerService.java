package com.arcclaw.channel.web;

import jakarta.enterprise.context.ApplicationScoped;

import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

/**
 * Server-side HTML sanitizer for LLM-generated content.
 * <p>
 * Uses OWASP Java HTML Sanitizer (allowlist-based) to strip dangerous tags and attributes
 * before sending HTML to WebSocket clients. This provides defense-in-depth
 * against XSS attacks that might bypass client-side DOMPurify.
 */
@ApplicationScoped
public class HtmlSanitizerService {

    private final PolicyFactory policyFactory;

    public HtmlSanitizerService() {
        policyFactory = new HtmlPolicyBuilder()
            .allowElements(
                "p", "br", "strong", "em", "b", "i", "u",
                "code", "pre", "span",
                "ul", "ol", "li",
                "a",
                "h1", "h2", "h3", "h4", "h5", "h6",
                "blockquote", "q", "cite",
                "table", "thead", "tbody", "tr", "th", "td",
                "img",
                "sub", "sup",
                "hr",
                "div"
            )
            .allowAttributes("href").onElements("a")
            .allowAttributes("target").onElements("a")
            .allowAttributes("title").onElements("a", "abbr", "acronym", "img")
            .allowAttributes("rel").onElements("a")
            .allowAttributes("src").onElements("img")
            .allowAttributes("alt").onElements("img")
            .allowAttributes("class").onElements("code", "span", "div")
            .allowAttributes("colspan", "rowspan").onElements("td", "th")
            .allowAttributes("scope").onElements("th")
            .allowAttributes("cite").onElements("blockquote", "q")
            .allowStandardUrlProtocols()
            .toFactory();
    }

    /**
     * Sanitizes HTML content by removing all dangerous tags and attributes.
     * Uses an allowlist-based approach (only known-safe elements are preserved).
     *
     * @param html raw HTML from LLM (potentially malicious)
     * @return sanitized HTML safe for rendering in browser
     */
    public String sanitize(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        try {
            return policyFactory.sanitize(html);
        } catch (Exception e) {
            System.err.println("HtmlSanitizerService: sanitization failed: " + e.getMessage());
            return "";
        }
    }

    /**
     * Strips all HTML tags, returning plain text only.
     * Use for channels that don't support rich text.
     *
     * @param html raw HTML
     * @return plain text with all tags removed
     */
    public String stripToText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        try {
            String sanitized = policyFactory.sanitize(html);
            return sanitized.replaceAll("<[^>]*>", "").trim();
        } catch (Exception e) {
            System.err.println("HtmlSanitizerService: text extraction failed: " + e.getMessage());
            return "";
        }
    }
}
