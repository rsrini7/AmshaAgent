package com.amsha.app.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.amsha.channel.api.ApprovalPrompt;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ContentNode;

import org.junit.jupiter.api.Test;

class CliReplyRendererTest {

    @Test
    void testSupports() {
        assertEquals(ChannelType.CLI, new CliReplyRenderer().supports());
    }

    @Test
    void renderContentEmptyAndNull() {
        CliReplyRenderer renderer = new CliReplyRenderer();
        assertEquals("", renderer.renderContent(null));
        assertEquals("", renderer.renderContent(List.of()));
    }

    @Test
    void renderContentAllNodeTypes() {
        CliReplyRenderer renderer = new CliReplyRenderer();
        var nodes = List.<ContentNode>of(
                new ContentNode.Text("Hello world"),
                new ContentNode.Heading("Title", 1),
                new ContentNode.CodeBlock("int a = 1;", "java"),
                new ContentNode.InlineCode("a"),
                new ContentNode.BulletList(List.of("item1", "item2")),
                new ContentNode.NumberedList(List.of("step1", "step2")),
                new ContentNode.KeyValue("key", "val"),
                new ContentNode.Separator(),
                new ContentNode.ErrorMessage("Something failed", "ERR_CODE"));

        String rendered = renderer.renderContent(nodes);
        assertTrue(rendered.contains("Hello world"));
        assertTrue(rendered.contains("Title"));
        assertTrue(rendered.contains("int a = 1;"));
        assertTrue(rendered.contains("item1"));
        assertTrue(rendered.contains("step1"));
        assertTrue(rendered.contains("key"));
        assertTrue(rendered.contains("val"));
        assertTrue(rendered.contains("---"));
        assertTrue(rendered.contains("Something failed"));
    }

    @Test
    void testRenderInteractionApprovalPrompt() {
        CliReplyRenderer renderer = new CliReplyRenderer();
        ApprovalPrompt ap = new ApprovalPrompt("web_search", "{\"q\":\"test\"}", "sess-1");

        String rendered = renderer.renderInteraction(ap);
        assertTrue(rendered.contains("Approval needed for tool: web_search"));
        assertTrue(rendered.contains("Params: {\"q\":\"test\"}"));
    }
}
