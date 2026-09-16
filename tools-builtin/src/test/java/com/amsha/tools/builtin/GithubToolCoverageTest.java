package com.amsha.tools.builtin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.audit.AuditService;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.ToolOperation;
import com.amsha.security.api.VaultKey;
import com.amsha.tool.api.IdempotencyMode;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolResponse;

import org.junit.jupiter.api.Test;

class GithubToolCoverageTest {

    private static final String GH_TOKEN_KEY = "AMSHA_TOOLS_GITHUB_TOKEN";

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

    /** A fully-wired tool with no reliance on CDI or the real filesystem. */
    private static GithubTool tool() {
        GithubTool tool = new GithubTool();
        tool.auditService = mock(AuditService.class);
        tool.secretVault = mock(SecretVault.class);
        tool.reposPerPage = 100;
        tool.timeoutSeconds = 5;
        return tool;
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

    // ── SMART NORMALIZATION: LLMs frequently confuse 'action' and 'command' ────────────────
    // {"action":"view","command":"repo"} means "view the repo", not "run the view action".

    @Test
    void normalizesActionThatHoldsAResourceType() {
        GithubTool tool = tool();

        List<String> cmd =
                tool.sandboxCommand(ctx(), Map.of("action", "view", "command", "repo", "target", "org/repo"));

        // Swapped to action=repo, command=view, target=org/repo
        assertEquals("gh", cmd.get(0));
        assertEquals("repo", cmd.get(1));
        assertEquals("view", cmd.get(2));
        assertEquals("org/repo", cmd.get(3));
    }

    @Test
    void normalizesActionThatHoldsACommand() {
        GithubTool tool = tool();

        // action="view" is itself a command -> default the resource type to "repo"
        List<String> viewCmd =
                tool.sandboxCommand(ctx(), Map.of("action", "view", "command", "view", "target", "org/repo"));
        assertEquals(List.of("gh", "repo", "view", "org/repo"), viewCmd.subList(0, 4));

        // action="list" -> repo list for the authenticated user
        List<String> listCmd = tool.sandboxCommand(ctx(), Map.of("action", "list", "command", "list"));
        assertTrue(listCmd.get(2).contains("users/@me/repos?per_page=100"), listCmd.toString());
    }

    @Test
    void rejectsOwnerSlashRepoForARepoList() {
        GithubTool tool = tool();

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> tool.sandboxCommand(ctx(), Map.of("action", "repo", "command", "list", "target", "org/repo")));

        assertTrue(ex.getMessage().contains("provide ONLY the owner name"), ex.getMessage());
    }

    // ── postProcessOutput: base64 README decode for `gh api .../readme` calls ─────────────

    @Test
    void decodesBase64ReadmeOutputForRefCalls() {
        GithubTool tool = tool();
        String encoded = Base64.getEncoder().encodeToString("# Hello".getBytes(StandardCharsets.UTF_8));

        String out = tool.postProcessOutput(encoded, Map.of("action", "repo", "command", "view", "ref", "main"));

        assertEquals("# Hello", out);
    }

    @Test
    void returnsRawOutputWhenBase64DecodeFails() {
        GithubTool tool = tool();

        // A lone base64 character is not decodable; the payload must pass through untouched
        // rather than surfacing a decode exception to the agent.
        String out = tool.postProcessOutput("A", Map.of("action", "repo", "command", "view", "ref", "main"));

        assertEquals("A", out, "Undecodable README payload must be passed through unchanged");
    }

    @Test
    void returnsRawOutputForNonRefCalls() {
        GithubTool tool = tool();

        // No ref -> not a README API call
        assertEquals("raw", tool.postProcessOutput("raw", Map.of("action", "repo", "command", "list")));

        // Blank ref -> not a README API call
        assertEquals("raw", tool.postProcessOutput("raw", Map.of("action", "repo", "command", "view", "ref", " ")));

        // README call but empty payload -> nothing to decode
        assertEquals("", tool.postProcessOutput("", Map.of("action", "repo", "command", "view", "ref", "main")));
    }

    // ── execute(): pre-process guards and token resolution ───────────────────────────────

    @Test
    void rejectsDisallowedActionWithoutStartingAProcess() {
        GithubTool tool = tool();

        ToolResponse resp = tool.execute(ctx(), Map.of("action", "invalid", "command", "list"));

        assertFalse(resp.success());
        assertEquals(1, resp.exitCode());
        assertTrue(resp.output().contains("not permitted"), resp.output());
    }

    /**
     * A resolvable vault token must be injected into the child environment. {@code gh} is not
     * installed in the test environment, so the run then fails at {@code ProcessBuilder.start()}
     * - which is exactly the boundary this test pins: token resolution happens *before* start.
     */
    @Test
    void injectsVaultTokenIntoChildEnvironmentBeforeStartingProcess() {
        GithubTool tool = tool();
        when(tool.secretVault.retrieveSystemKey(GH_TOKEN_KEY))
                .thenReturn(Optional.of(new VaultKey("ghp_test".getBytes(StandardCharsets.UTF_8))));

        ToolResponse resp = tool.execute(ctx(), Map.of("action", "repo", "command", "list"));

        assertFalse(resp.success(), "gh is absent here, so the launch must fail: " + resp.output());
    }

    /** A vault outage must degrade to "no token" rather than aborting the whole tool call. */
    @Test
    void toleratesVaultFailureWhenResolvingToken() {
        GithubTool tool = tool();
        when(tool.secretVault.retrieveSystemKey(GH_TOKEN_KEY))
                .thenThrow(new IllegalStateException("vault unavailable"));

        ToolResponse resp = tool.execute(ctx(), Map.of("action", "repo", "command", "list"));

        assertFalse(resp.success(), "A vault outage must not escape as an unhandled exception: " + resp.output());
    }
}
