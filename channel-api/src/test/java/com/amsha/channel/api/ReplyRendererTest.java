package com.amsha.channel.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class ReplyRendererTest {

    static class SimpleRenderer implements ReplyRenderer {
        @Override
        public ChannelType supports() {
            return ChannelType.CLI;
        }

        @Override
        public String renderContent(List<ContentNode> nodes) {
            StringBuilder sb = new StringBuilder();
            for (ContentNode node : nodes) {
                if (node instanceof ContentNode.Text t) {
                    sb.append(t.content());
                }
            }
            return sb.toString();
        }

        @Override
        public String renderInteraction(InteractionNode node) {
            return node.fallbackText();
        }
    }

    @Test
    void testRenderer() {
        SimpleRenderer renderer = new SimpleRenderer();
        assertEquals(ChannelType.CLI, renderer.supports());
        assertEquals("", renderer.render(null));
        assertEquals("", renderer.render(List.of()));

        ContentNode.Text text = new ContentNode.Text("hello world");
        ApprovalPrompt prompt = new ApprovalPrompt("host_exec", "{}", "sess1");

        String rendered = renderer.render(List.of(text, prompt));
        assertNotNull(rendered);
        assertTrue(rendered.contains("hello world"));
        assertTrue(rendered.contains("host_exec"));
    }
}
