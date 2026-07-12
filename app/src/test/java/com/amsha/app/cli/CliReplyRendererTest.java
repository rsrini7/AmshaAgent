package com.amsha.app.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amsha.channel.api.ApprovalPrompt;

import org.junit.jupiter.api.Test;

class CliReplyRendererTest {

    @Test
    void approvalPromptIncludesParamsAndActions() {
        String rendered = new CliReplyRenderer()
                .renderInteraction(new ApprovalPrompt("execute_shell", "{\"command\":\"ls\"}", "session-1"));

        assertTrue(rendered.contains("execute_shell"));
        assertTrue(rendered.contains("{\"command\":\"ls\"}"));
        assertTrue(rendered.contains("/approve"));
        assertTrue(rendered.contains("/deny"));
    }
}
