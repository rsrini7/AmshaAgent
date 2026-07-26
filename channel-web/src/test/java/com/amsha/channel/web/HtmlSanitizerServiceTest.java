package com.amsha.channel.web;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HtmlSanitizerServiceTest {

    private final HtmlSanitizerService service = new HtmlSanitizerService();

    @Test
    void testSanitizeNullOrBlank() {
        assertEquals("", service.sanitize(null));
        assertEquals("", service.sanitize("   "));
    }

    @Test
    void testSanitizeAllowedElements() {
        String input = "<p>Hello <strong>world</strong></p><a href=\"https://example.com\">link</a>";
        String result = service.sanitize(input);
        assertTrue(result.contains("<p>Hello <strong>world</strong></p>"));
        assertTrue(result.contains("<a href=\"https://example.com\""));
    }

    @Test
    void testSanitizeStripsScriptTags() {
        String input = "<p>Safe</p><script>alert('xss')</script>";
        String result = service.sanitize(input);
        assertFalse(result.contains("<script>"));
        assertFalse(result.contains("alert"));
        assertTrue(result.contains("<p>Safe</p>"));
    }

    @Test
    void testSanitizeStripsOnclickAttribute() {
        String input = "<button onclick=\"alert(1)\">Click me</button>";
        String result = service.sanitize(input);
        assertFalse(result.contains("onclick"));
        assertFalse(result.contains("alert"));
    }

    @Test
    void testStripToTextNullOrBlank() {
        assertEquals("", service.stripToText(null));
        assertEquals("", service.stripToText("   "));
    }

    @Test
    void testStripToTextRemovesTags() {
        String input = "<h1>Title</h1><p>Paragraph with <em>emphasis</em>.</p>";
        String result = service.stripToText(input);
        assertEquals("TitleParagraph with emphasis.", result);
    }
}
