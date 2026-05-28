package com.arcclaw.channel.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.arcclaw.channel.api.ApprovalPrompt;
import com.arcclaw.channel.api.ContentNode;
import com.arcclaw.channel.api.ReplyNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TelegramMarkdownV2RendererTest {

    private final TelegramMarkdownV2Renderer renderer = new TelegramMarkdownV2Renderer();

    @Test
    void testDotEscaped() {
        String output = renderer.render(List.<ReplyNode>of(new ContentNode.Text("v1.0")));
        assertEquals("v1\\.0", output);
    }

    @Test
    void testDashEscaped() {
        String output = renderer.render(List.<ReplyNode>of(new ContentNode.Text("step-1")));
        assertEquals("step\\-1", output);
    }

    @Test
    void testParenthesesEscaped() {
        String output = renderer.render(List.<ReplyNode>of(new ContentNode.Text("(note)")));
        assertEquals("\\(note\\)", output);
    }

    @Test
    void testNormalWordNotMutated() {
        String output = renderer.render(List.<ReplyNode>of(new ContentNode.Text("hello world")));
        assertEquals("hello world", output);
    }

    @Test
    void testCodeBlockNotEscaped() {
        String code = "system.out.println(\"hello\");";
        String output = renderer.render(List.<ReplyNode>of(new ContentNode.CodeBlock(code, "java")));
        assertTrue(output.contains(code), "Code block content must not be escaped");
        assertTrue(output.startsWith("```java"), "Should have language hint");
    }

    @Test
    void testCodeBlockLanguageNotEscaped() {
        String code = "int x = 1;";
        String output = renderer.render(List.<ReplyNode>of(new ContentNode.CodeBlock(code, "c++")));
        assertTrue(output.startsWith("```c++"), "C++ language hint must NOT be escaped to c\\+\\+");
    }

    @Test
    void testCodeBlockLanguageJavaUnchanged() {
        String code = "public class A {}";
        String output = renderer.render(List.<ReplyNode>of(new ContentNode.CodeBlock(code, "java")));
        assertTrue(output.startsWith("```java"), "Java language hint must remain unchanged");
    }

    @Test
    void testCodeBlockNullLanguage() {
        String code = "plain text";
        String output = renderer.render(List.<ReplyNode>of(new ContentNode.CodeBlock(code, null)));
        assertTrue(output.startsWith("```\n"), "Null language should render as empty code block header");
    }

    @Test
    void testPlainTextSpecialCharactersEscaped() {
        String output = renderer.render(List.<ReplyNode>of(new ContentNode.Text("Hello! This is a test.")));
        assertTrue(output.contains("Hello\\!"), "Exclamation mark should be escaped");
        assertTrue(output.contains("test\\."), "Period should be escaped");
    }

    @Test
    void testBulletListFormatting() {
        String output = renderer.render(List.<ReplyNode>of(new ContentNode.BulletList(List.of("one", "two"))));
        assertEquals("• one\n• two", output);
    }

    @Test
    void testNumberedListEscapesDots() {
        String output = renderer.render(List.<ReplyNode>of(new ContentNode.NumberedList(List.of("first", "second"))));
        assertEquals("1\\. first\n2\\. second", output);
    }

    @Test
    void testApprovalPromptFormat() {
        String output = renderer.render(List.<ReplyNode>of(new ApprovalPrompt("gh-tool", "{\"id\":1}", "sess-123")));
        assertTrue(output.contains("APPROVAL REQUIRED"));
        assertTrue(output.contains("gh\\-tool"));
        assertTrue(output.contains("sess\\-123"));
        assertTrue(output.contains("json"));
    }

    @Test
    void testErrorMessageFormat() {
        String output = renderer.render(List.<ReplyNode>of(new ContentNode.ErrorMessage("something broke", "SYSTEM")));
        assertTrue(output.contains("❌"));
        assertTrue(output.contains("SYSTEM"));
        assertTrue(output.contains("something broke"));
    }

    @Test
    void testSeparatorOutput() {
        String output = renderer.render(List.<ReplyNode>of(new ContentNode.Separator()));
        assertEquals("---", output);
    }

    @Test
    void testH1RendersAsBoldUnderline() {
        String output = renderer.render(List.of(new ContentNode.Heading("Title", 1)));
        assertEquals("*__Title__*", output);
    }

    @Test
    void testH2RendersAsBold() {
        String output = renderer.render(List.of(new ContentNode.Heading("Section", 2)));
        assertEquals("*Section*", output);
    }

    @Test
    void testH3ThroughH6RendersAsBold() {
        assertEquals("*H3*", renderer.render(List.of(new ContentNode.Heading("H3", 3))));
        assertEquals("*H6*", renderer.render(List.of(new ContentNode.Heading("H6", 6))));
    }

    @Test
    void testHeadingContentIsStillEscaped() {
        String output = renderer.render(List.of(new ContentNode.Heading("H1_With_Underscore", 1)));
        assertEquals("*__H1\\_With\\_Underscore__*", output);
    }

    // All 18 special characters must be escaped with a backslash prefix.
    @ParameterizedTest
    @ValueSource(chars = {'_', '*', '[', ']', '(', ')', '~', '`', '>', '#', '+', '-', '=', '|', '{', '}', '.', '!'})
    void escapesSingleSpecialChar(char specialChar) {
        String input = String.valueOf(specialChar);
        String result = renderer.esc(input);
        assertEquals("\\" + specialChar, result);
    }

    // Backslash itself must be escaped first (\\)
    @Test
    void escapesBackslash() {
        assertEquals("\\\\", renderer.esc("\\"));
    }

    // Normal ASCII must pass through unchanged
    @Test
    void normalTextPassesThrough() {
        assertEquals("hello world", renderer.esc("hello world"));
    }
}
