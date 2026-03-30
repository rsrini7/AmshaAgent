package com.arcclaw.channel.web;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import com.arcclaw.channel.api.ReplyNode;
import org.junit.jupiter.api.Test;

class WebReplyRendererTest {

    private final WebReplyRenderer renderer = new WebReplyRenderer();

    @Test
    void testRenderText() {
        String result = renderer.render(List.of(new ReplyNode.Text("Hello World")));
        assertEquals("Hello World", result);
    }

    @Test
    void testRenderHeading() {
        String result = renderer.render(List.of(new ReplyNode.Heading("My Title", 2)));
        assertEquals("## My Title", result);
    }

    @Test
    void testRenderCodeBlock() {
        String result = renderer.render(List.of(new ReplyNode.CodeBlock("println('ok')", "java")));
        assertTrue(result.contains("```java"));
        assertTrue(result.contains("println('ok')"));
        assertTrue(result.endsWith("```"));
    }

    @Test
    void testRenderBulletList() {
        String result = renderer.render(List.of(new ReplyNode.BulletList(List.of("A", "B"))));
        assertEquals("- A\n- B", result);
    }

    @Test
    void testRenderMultipleNodes() {
        String result = renderer.render(List.of(
            new ReplyNode.Heading("Title", 1),
            new ReplyNode.Text("Subtitle")
        ));
        assertEquals("# Title\n\nSubtitle", result);
    }
}
