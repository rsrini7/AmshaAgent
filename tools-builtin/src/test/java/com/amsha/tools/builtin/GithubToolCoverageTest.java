package com.amsha.tools.builtin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.audit.AuditService;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.ToolOperation;
import com.amsha.tool.api.IdempotencyMode;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolResponse;

import org.junit.jupiter.api.Test;

class GithubToolCoverageTest {

    private ToolExecutionContext ctx() {
        return new ToolExecutionContext(
                "sess1",
                "u1",
                "chat1",
                "cli",
                AutonomyLevel.FULL,
                "agent1",
                "researcher",
                null,
                null,
                null,
                null,
                null);
    }

    @Test
    void testMetadata() {
        GithubTool tool = new GithubTool();
        assertEquals("github", tool.name());
        assertTrue(tool.requiresSandbox());
        assertTrue(tool.isEnabled());
        assertEquals(ToolOperation.NETWORK_OUTBOUND, tool.requiredOperation());
        assertEquals(IdempotencyMode.IDEMPOTENT, tool.idempotencyMode());
        assertNotNull(tool.description());
        assertNotNull(tool.parametersSchema());
        assertFalse(tool.requiresManualApproval(Map.of()));
    }

    @Test
    void testSandboxCommandValidation() {
        GithubTool tool = new GithubTool();
        tool.auditService = mock(AuditService.class);
        tool.reposPerPage = 50;

        // Valid repo view with ref
        List<String> cmd1 = tool.sandboxCommand(
                ctx(),
                Map.of(
                        "action", "repo",
                        "command", "view",
                        "target", "org/repo",
                        "ref", "main"));
        assertTrue(cmd1.contains("gh"));
        assertTrue(cmd1.contains("api"));
        assertTrue(cmd1.get(2).contains("repos/org/repo/readme?ref=main"));

        // Valid gist list
        List<String> cmd2 = tool.sandboxCommand(
                ctx(),
                Map.of(
                        "action", "gist",
                        "command", "list"));
        assertTrue(cmd2.contains("gist"));
        assertTrue(cmd2.contains("list"));

        // Default action="repo", command="list" works
        List<String> cmdDefault = tool.sandboxCommand(ctx(), Map.of("command", "list"));
        assertNotNull(cmdDefault);
        assertTrue(cmdDefault.get(2).contains("repos?per_page="));

        // Invalid action
        assertThrows(
                IllegalArgumentException.class,
                () -> tool.sandboxCommand(ctx(), Map.of("action", "invalid", "command", "list")));

        // Missing command
        assertThrows(IllegalArgumentException.class, () -> tool.sandboxCommand(ctx(), Map.of("action", "repo")));

        // Invalid command
        assertThrows(
                IllegalArgumentException.class,
                () -> tool.sandboxCommand(ctx(), Map.of("action", "repo", "command", "delete")));

        // Repo view missing target
        assertThrows(
                IllegalArgumentException.class,
                () -> tool.sandboxCommand(ctx(), Map.of("action", "repo", "command", "view")));

        // Invalid target format (no slash for repo view)
        assertThrows(
                IllegalArgumentException.class,
                () -> tool.sandboxCommand(
                        ctx(),
                        Map.of(
                                "action", "repo",
                                "command", "view",
                                "target", "invalid-no-slash")));
    }

    @Test
    void testExecuteDirectlyFailsGracefullyWhenNoToken() {
        GithubTool tool = new GithubTool();
        tool.secretVault = mock(SecretVault.class);
        tool.auditService = mock(AuditService.class);
        when(tool.secretVault.retrieveSystemKey("GITHUB_TOKEN")).thenReturn(Optional.empty());

        ToolResponse resp = tool.execute(ctx(), Map.of("action", "repo", "command", "list"));
        assertNotNull(resp);
        assertFalse(resp.success());
    }
}
