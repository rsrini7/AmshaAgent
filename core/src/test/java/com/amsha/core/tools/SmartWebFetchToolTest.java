package com.amsha.core.tools;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SmartWebFetchToolTest {

    @Test
    void htmlToTextStripsTags() {
        String html = "<html><body><h1>Title</h1><p>Hello <b>world</b></p></body></html>";
        String text = SmartWebFetchTool.htmlToText(html);
        assertTrue(text.contains("Title"));
        assertTrue(text.contains("Hello world"));
        assertFalse(text.contains("<"));
    }

    @Test
    void htmlToTextRemovesScriptAndStyle() {
        String html = "<html><head><script>var x=1;</script><style>.a{color:red}</style></head>"
                + "<body><p>Visible</p></body></html>";
        String text = SmartWebFetchTool.htmlToText(html);
        assertTrue(text.contains("Visible"));
        assertFalse(text.contains("var x"));
        assertFalse(text.contains("color:red"));
    }

    @Test
    void htmlToTextDecodesEntities() {
        String html = "<p>Hello &amp; welcome &lt;user&gt;</p>";
        String text = SmartWebFetchTool.htmlToText(html);
        assertTrue(text.contains("Hello & welcome"));
        assertTrue(text.contains("<user>"));
    }

    @Test
    void htmlToTextPreservesNewlines() {
        String html = "<p>First paragraph</p><p>Second paragraph</p>";
        String text = SmartWebFetchTool.htmlToText(html);
        assertTrue(text.contains("\n"));
    }

    @Test
    void truncateAtSentencePrefersSentenceBoundary() {
        String text = "This is a short sentence. This is another sentence that goes on for a while.";
        String truncated = SmartWebFetchTool.truncateAtSentence(text, 40);
        assertTrue(truncated.endsWith("."));
        assertTrue(truncated.length() <= 40);
    }

    @Test
    void truncateAtSentenceFallsBackToHardLimit() {
        String text = "No sentence boundary here just a very long string without any punctuation marks";
        String truncated = SmartWebFetchTool.truncateAtSentence(text, 30);
        assertTrue(truncated.length() <= 30);
    }

    @Test
    void truncateAtSentenceReturnsFullTextIfShortEnough() {
        String text = "Short.";
        String truncated = SmartWebFetchTool.truncateAtSentence(text, 100);
        assertEquals(text, truncated);
    }
}
