package com.arcclaw.channel.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MarkdownEscapeServiceTest {

    private final MarkdownEscapeService service = new MarkdownEscapeService();
    private final MarkdownProfile profile = MarkdownProfile.TELEGRAM_V2;

    @ParameterizedTest
    @ValueSource(chars = {'_','*','[',']','(',')',
        '~','`','>','#','+','-','=','|','{','}','.','!'})
    void testSingleSpecialCharEscaped(char c) {
        String input = String.valueOf(c);
        String expected = "\\" + c;
        assertEquals(expected, service.escape(input, profile));
    }

    @Test
    void testStandardTextPassesThrough() {
        assertEquals("Hello world", service.escape("Hello world", profile));
    }

    @Test
    void testAlreadyEscapedCharsNotDoubleEscaped() {
        // If a character is already backslash-escaped, it shouldn't get another backslash
        String input = "Hello \\. World";
        assertEquals("Hello \\. World", service.escape(input, profile));
        
        String input2 = "\\*bold\\*";
        assertEquals("\\*bold\\*", service.escape(input2, profile));
    }

    @Test
    void testBackslashIsEscapedWhenLone() {
        // A single backslash should become \\
        assertEquals("a\\\\b", service.escape("a\\b", profile));
    }

    @Test
    void testCorpusNestedMarkdownStructures() {
        // Tests related to string corpora that look like nested markdown
        String input1 = "This is *bold* and _italic_ and `code`";
        // Expected for TelegramV2 is that they all get escaped so renderer can handle them explicitly if we map directly,
        // Wait, the esc() method escapes *everything* blindly, so even nested markdown will just have its syntax tokens escaped.
        String expected1 = "This is \\*bold\\* and \\_italic\\_ and \\`code\\`";
        assertEquals(expected1, service.escape(input1, profile));
        
        String input2 = "[Link](http://example.com)";
        String expected2 = "\\[Link\\]\\(http://example\\.com\\)";
        assertEquals(expected2, service.escape(input2, profile));
    }

    @Test
    void testNullAndEmpty() {
        assertEquals("", service.escape(null, profile));
        assertEquals("", service.escape("", profile));
    }
}
