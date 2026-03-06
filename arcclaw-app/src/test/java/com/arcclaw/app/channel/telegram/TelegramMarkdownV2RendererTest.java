package com.arcclaw.app.channel.telegram;

import com.arcclaw.channel.api.ReplyNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramMarkdownV2RendererTest {

    private final TelegramMarkdownV2Renderer renderer = new TelegramMarkdownV2Renderer();

    @Test
    void testDotEscaped() {
        String output = renderer.render(List.of(new ReplyNode.Text("v1.0")));
        assertEquals("v1\\.0", output);
    }

    @Test
    void testDashEscaped() {
        String output = renderer.render(List.of(new ReplyNode.Text("step-1")));
        assertEquals("step\\-1", output);
    }

    @Test
    void testParenthesesEscaped() {
        String output = renderer.render(List.of(new ReplyNode.Text("(note)")));
        assertEquals("\\(note\\)", output);
    }

    @Test
    void testNormalWordNotMutated() {
        String output = renderer.render(List.of(new ReplyNode.Text("hello world")));
        assertEquals("hello world", output);
    }

    @Test
    void testCodeBlockNotEscaped() {
        String code = "system.out.println(\"hello\");";
        String output = renderer.render(List.of(new ReplyNode.CodeBlock(code, "java")));
        assertTrue(output.contains(code), "Code block content must not be escaped");
        assertTrue(output.startsWith("```java"), "Should have language hint");
    }

    @Test
    void testCodeBlockLanguageNotEscaped() {
        String code = "int x = 1;";
        String output = renderer.render(List.of(new ReplyNode.CodeBlock(code, "c++")));
        assertTrue(output.startsWith("```c++"), "C++ language hint must NOT be escaped to c\\+\\+");
    }

    @Test
    void testCodeBlockLanguageJavaUnchanged() {
        String code = "public class A {}";
        String output = renderer.render(List.of(new ReplyNode.CodeBlock(code, "java")));
        assertTrue(output.startsWith("```java"), "Java language hint must remain unchanged");
    }

    @Test
    void testCodeBlockNullLanguage() {
        String code = "plain text";
        String output = renderer.render(List.of(new ReplyNode.CodeBlock(code, null)));
        assertTrue(output.startsWith("```\n"), "Null language should render as empty code block header");
    }

    @Test
    void testPlainTextSpecialCharactersEscaped() {
        String output = renderer.render(List.of(new ReplyNode.Text("Hello! This is a test.")));
        assertTrue(output.contains("Hello\\!"), "Exclamation mark should be escaped");
        assertTrue(output.contains("test\\."), "Period should be escaped");
    }

    @Test
    void testBulletListFormatting() {
        String output = renderer.render(List.of(new ReplyNode.BulletList(List.of("one", "two"))));
        assertEquals("• one\n• two", output);
    }

    @Test
    void testNumberedListEscapesDots() {
        String output = renderer.render(List.of(new ReplyNode.NumberedList(List.of("first", "second"))));
        assertEquals("1\\. first\n2\\. second", output);
    }

    @Test
    void testApprovalPromptFormat() {
        String output = renderer.render(List.of(new ReplyNode.ApprovalPrompt("gh-tool", "{\"id\":1}", "sess-123")));
        assertTrue(output.contains("APPROVAL REQUIRED"));
        assertTrue(output.contains("gh\\-tool"));
        assertTrue(output.contains("sess\\-123"));
        assertTrue(output.contains("json"));
    }

    @Test
    void testErrorMessageFormat() {
        String output = renderer.render(List.of(new ReplyNode.ErrorMessage("something broke", "SYSTEM")));
        assertTrue(output.contains("❌"));
        assertTrue(output.contains("SYSTEM"));
        assertTrue(output.contains("something broke"));
    }

    @Test
    void testSeparatorOutput() {
        String output = renderer.render(List.of(new ReplyNode.Separator()));
        assertEquals("———", output);
    }
}
