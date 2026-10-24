package com.amsha.tools.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.ToolExecutionContext;

import org.junit.jupiter.api.Test;

class HostExecBrowserPreviewTest {

    @Test
    void browserPreviewGoal_rewrites_html_inspection_to_opener_command() {
        HostExecTool tool = new HostExecTool();
        ToolExecutionContext ctx = new ToolExecutionContext(
                "test-session",
                "user1",
                "chat1",
                "skill:algorithmic-art",
                AutonomyLevel.FULL,
                "trace-1",
                null,
                null,
                "use algorithmic art to generate very simple particle system open in browser",
                null,
                null,
                null);

        var rewrite = tool.rewriteBrowserPreviewIfNeeded(ctx, "cat /tmp/viewer.html");

        assertEquals("/tmp/viewer.html", rewrite.targetPath());
        assertTrue(rewrite.code().contains("open") || rewrite.code().contains("xdg-open"));
        assertFalse(rewrite.code().contains("cat /tmp/viewer.html"));
    }

    @Test
    void browserPreviewGoal_doesNotAutoOpen_template_files() {
        HostExecTool tool = new HostExecTool();
        ToolExecutionContext ctx = new ToolExecutionContext(
                "test-session",
                "user1",
                "chat1",
                "skill:algorithmic-art",
                AutonomyLevel.FULL,
                "trace-1",
                null,
                null,
                "use algorithmic art to generate very simple particle system open in browser",
                null,
                null,
                null);

        String code = "cat /tmp/amsha/skills/skillsjars/algorithmic-art/templates/viewer.html";
        var rewrite = tool.rewriteBrowserPreviewIfNeeded(ctx, code);

        assertEquals(null, rewrite.targetPath());
        assertEquals(code, rewrite.code());
    }
}
