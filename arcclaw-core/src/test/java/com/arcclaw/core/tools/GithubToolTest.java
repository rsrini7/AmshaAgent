package com.arcclaw.core.tools;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class GithubToolTest {

    private final GithubTool tool = new GithubTool();

    @Test
    void testDeleteCommandBlocked() {
        Map<String, Object> params = Map.of(
            "action", "repo",
            "command", "delete",
            "target", "rsrini7/arcclaw"
        );
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> tool.sandboxCommand(params));
        assertTrue(exception.getMessage().contains("not permitted"));
    }

    @Test
    void testArchiveCommandBlocked() {
        Map<String, Object> params = Map.of(
            "action", "repo",
            "command", "archive",
            "target", "rsrini7/arcclaw"
        );
        assertThrows(IllegalArgumentException.class, () -> tool.sandboxCommand(params));
    }

    @Test
    void testViewStillWorks() {
        Map<String, Object> params = Map.of(
            "action", "repo",
            "command", "view",
            "target", "rsrini7/arcclaw"
        );
        assertDoesNotThrow(() -> tool.sandboxCommand(params));
    }

    @Test
    void testListStillWorks() {
        Map<String, Object> params = Map.of(
            "action", "repo",
            "command", "list",
            "target", "rsrini7"
        );
        var cmd = tool.sandboxCommand(params);
        // cmd is ["gh", "api", "users/rsrini7/repos?per_page=30"]
        assertTrue(cmd.stream().anyMatch(s -> s.contains("rsrini7")));
    }

    @Test
    void testGistListPreservesTarget() {
        Map<String, Object> params = Map.of(
            "action", "gist",
            "command", "list",
            "target", "rsrini7"
        );
        var cmd = tool.sandboxCommand(params);
        assertTrue(cmd.contains("api"));
        assertTrue(cmd.stream().anyMatch(s -> s.contains("rsrini7")), "gist list SHOULD contain username when targeting another user");
    }

    @Test
    void testGistViewRequiresTarget() {
        Map<String, Object> params = Map.of(
            "action", "gist",
            "command", "view"
        );
        assertThrows(IllegalArgumentException.class, () -> tool.sandboxCommand(params));
    }

    @Test
    void testGistViewWithId() {
        Map<String, Object> params = Map.of(
            "action", "gist",
            "command", "view",
            "target", "abc-123"
        );
        var cmd = tool.sandboxCommand(params);
        assertTrue(cmd.contains("abc-123"));
    }
}
