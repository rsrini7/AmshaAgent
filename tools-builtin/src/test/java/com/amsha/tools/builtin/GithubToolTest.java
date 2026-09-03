package com.amsha.tools.builtin;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.amsha.core.audit.AuditService;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.ToolExecutionContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("java:S1192")
class GithubToolTest {

    @Mock
    AuditService auditService;

    @InjectMocks
    GithubTool tool;

    @Test
    void testDeleteCommandBlocked() {
        Map<String, Object> params = Map.of(
                "action", "repo",
                "command", "delete",
                "target", "rsrini7/amsha");
        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> tool.sandboxCommand(ctx(), params));
        assertTrue(exception.getMessage().contains("not permitted"));
    }

    @Test
    void testArchiveCommandBlocked() {
        Map<String, Object> params = Map.of(
                "action", "repo",
                "command", "archive",
                "target", "rsrini7/amsha");
        assertThrows(IllegalArgumentException.class, () -> tool.sandboxCommand(ctx(), params));
    }

    @Test
    void testViewStillWorks() {
        Map<String, Object> params = Map.of(
                "action", "repo",
                "command", "view",
                "target", "rsrini7/amsha");
        assertDoesNotThrow(() -> tool.sandboxCommand(ctx(), params));
    }

    @Test
    void testListStillWorks() {
        Map<String, Object> params = Map.of(
                "action", "repo",
                "command", "list",
                "target", "rsrini7");
        var cmd = tool.sandboxCommand(ctx(), params);
        // cmd is ["gh", "api", "users/rsrini7/repos?per_page=30"]
        assertTrue(cmd.stream().anyMatch(s -> s.contains("rsrini7")));
    }

    @Test
    void testGistListPreservesTarget() {
        Map<String, Object> params = Map.of(
                "action", "gist",
                "command", "list",
                "target", "rsrini7");
        var cmd = tool.sandboxCommand(ctx(), params);
        assertTrue(cmd.contains("api"));
        assertTrue(
                cmd.stream().anyMatch(s -> s.contains("rsrini7")),
                "gist list SHOULD contain username when targeting another user");
    }

    @Test
    void testGistListRejectsInvalidUsername() {
        Map<String, Object> params = Map.of(
                "action", "gist",
                "command", "list",
                "target", "../invalid;injection");
        assertThrows(IllegalArgumentException.class, () -> tool.sandboxCommand(ctx(), params));
    }

    @Test
    void testGistViewRequiresTarget() {
        Map<String, Object> params = Map.of(
                "action", "gist",
                "command", "view");
        assertThrows(IllegalArgumentException.class, () -> tool.sandboxCommand(ctx(), params));
    }

    @Test
    void testGistViewWithId() {
        Map<String, Object> params = Map.of(
                "action", "gist",
                "command", "view",
                "target", "abc-123");
        var cmd = tool.sandboxCommand(ctx(), params);
        assertTrue(cmd.contains("abc-123"));
    }

    private ToolExecutionContext ctx() {
        return new ToolExecutionContext(
                "s1", "u1", "c1", "coder", AutonomyLevel.FULL, "t1", null, null, null, null, null, null);
    }
}
