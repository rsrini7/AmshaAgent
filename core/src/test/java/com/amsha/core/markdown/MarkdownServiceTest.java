package com.amsha.core.markdown;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MarkdownServiceTest {

    private final MarkdownService service = new MarkdownService();

    @Test
    void renderToHtmlProducesHtml() {
        String html = service.renderToHtml("# Hello");
        assertTrue(html.contains("<h1>"));
        assertTrue(html.contains("Hello"));
    }

    @Test
    void renderToHtmlWithNullReturnsEmpty() {
        assertEquals("", service.renderToHtml(null));
    }

    @Test
    void renderToHtmlWithBlankReturnsEmpty() {
        assertEquals("", service.renderToHtml("  "));
    }

    @Test
    void renderToTextStripsFormatting() {
        String text = service.renderToText("**bold** and *italic*");
        assertTrue(text.contains("bold"));
        assertTrue(text.contains("italic"));
        assertFalse(text.contains("**"));
    }

    @Test
    void renderToTextWithNullReturnsEmpty() {
        assertEquals("", service.renderToText(null));
    }

    @Test
    void parseReturnsNonNull() {
        var doc = service.parse("# Hello\n\nWorld");
        assertNotNull(doc);
    }

    @Test
    void extractTitleFromH1() {
        String title = service.extractTitle("# My Title\n\nSome content");
        assertEquals("My Title", title);
    }

    @Test
    void extractTitleReturnsNullForNoH1() {
        assertNull(service.extractTitle("## Subtitle\n\nContent"));
    }

    @Test
    void extractTitleReturnsNullForNull() {
        assertNull(service.extractTitle(null));
    }

    @Test
    void extractTitleReturnsNullForBlank() {
        assertNull(service.extractTitle("  "));
    }

    @Test
    void renderTableExtension() {
        String md = "| a | b |\n|---|---|\n| 1 | 2 |";
        String html = service.renderToHtml(md);
        assertTrue(html.contains("<table>"));
    }
}
