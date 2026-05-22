package com.arcclaw.channel.web;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import com.arcclaw.channel.api.ContentNode;
import org.junit.jupiter.api.Test;

class WebReplyRendererTest {

    private final WebReplyRenderer renderer = new WebReplyRenderer();

    @Test
    void testRenderText() {
        String result = renderer.render(List.of(new ContentNode.Text("Hello World")));
        assertEquals("Hello World", result);
    }

    @Test
    void testRenderHeading() {
        String result = renderer.render(List.of(new ContentNode.Heading("My Title", 2)));
        assertEquals("## My Title", result);
    }

    @Test
    void testRenderCodeBlock() {
        String result = renderer.render(List.of(new ContentNode.CodeBlock("println('ok')", "java")));
        assertTrue(result.contains("```java"));
        assertTrue(result.contains("println('ok')"));
        assertTrue(result.endsWith("```"));
    }

    @Test
    void testRenderBulletList() {
        String result = renderer.render(List.of(new ContentNode.BulletList(List.of("A", "B"))));
        assertEquals("- A\n- B", result);
    }

    @Test
    void testRenderMultipleNodes() {
        String result = renderer.render(List.of(
            new ContentNode.Heading("Title", 1),
            new ContentNode.Text("Subtitle")
        ));
        assertEquals("# Title\n\nSubtitle", result);
    }
}
