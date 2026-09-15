package com.amsha.tools.builtin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.amsha.core.audit.AuditService;
import com.amsha.sandbox.DirectExecutor;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HostExecToolUnitTest {

    private static final String BROWSER_GOAL = "open in browser";

    @Mock
    AuditService auditService;

    @Mock
    HostJavaCompiler hostJavaCompiler;

    @Mock
    DirectExecutor directExecutor;

    @InjectMocks
    HostExecTool tool;

    @BeforeEach
    void setUp() throws Exception {
        tool.init();
        tool.workspaceRootDir = "./target/test-workspaces";
        // Lenient: only the paths that actually reach the executor consume this stub.
        lenient().when(directExecutor.executeDirectly(any(), any(), any())).thenReturn("ran");
    }

    private static ToolExecutionContext ctx(String goal, String skillHome) {
        return ctx(goal, skillHome, false);
    }

    private static ToolExecutionContext ctx(String goal, String skillHome, boolean browserPreview) {
        return new ToolExecutionContext(
                "s1",
                "u1",
                "c1",
                "coder",
                AutonomyLevel.FULL,
                "t1",
                null,
                null,
                goal,
                null,
                null,
                null,
                skillHome,
                browserPreview);
    }

    @Test
    void testMetadataAndSchema() {
        assertEquals(ToolCapability.HOST_EXEC.toolName(), tool.name());
        assertFalse(tool.requiresSandbox());
        assertNotNull(tool.parametersSchema());
        assertEquals(List.of("code"), tool.parametersSchema().required());
    }

    @Test
    void testExecuteNullOrEmptyParams() {
        ToolExecutionContext ctx = new ToolExecutionContext(
                "s1", "u1", "c1", "coder", AutonomyLevel.FULL, "t1", null, null, "Goal", null, null, null, null);

        ToolResponse nullRes = tool.execute(ctx, null);
        assertFalse(nullRes.success());
        assertTrue(nullRes.output().contains("Parameters are required"));

        ToolResponse emptyRes = tool.execute(ctx, Map.of());
        assertFalse(emptyRes.success());
        assertTrue(emptyRes.output().contains("'code' is required"));
    }

    @Test
    void testExecuteUnsupportedLanguage() {
        ToolExecutionContext ctx = new ToolExecutionContext(
                "s1", "u1", "c1", "coder", AutonomyLevel.FULL, "t1", null, null, "Goal", null, null, null, null);

        ToolResponse res = tool.execute(ctx, Map.of("code", "echo 1", "language", "unsupported_lang"));
        assertFalse(res.success());
        assertTrue(res.output().contains("language not supported"));
    }

    @Test
    void testPostProcessOutput() {
        String output = tool.postProcessOutput("hello world", Map.of("code", "echo 1"));
        assertNotNull(output);
    }

    // ── Parameter normalization ──────────────────────────────────────────────────────────

    /** 'command' is an accepted alias for 'code' - it is folded in before the null check. */
    @Test
    void commandAliasIsCopiedIntoCode() {
        Map<String, Object> params = new HashMap<>();
        params.put("command", "echo hi");

        ToolResponse res = tool.execute(ctx("Goal", null), params);

        assertTrue(res.success(), res.output());
        assertTrue(res.output().contains("ran"), res.output());
    }

    @Test
    void blankLanguageDefaultsToBash() {
        ToolResponse res = tool.execute(ctx("Goal", null), Map.of("code", "echo hi", "language", "   "));

        assertTrue(res.success(), res.output());
    }

    // ── Failure mapping ──────────────────────────────────────────────────────────────────

    /**
     * A workspace that cannot be created surfaces as an {@code IOException} and must be mapped
     * to a 1-exit failure with a non-blank message, never an unhandled exception.
     */
    @Test
    void ioFailureDuringWorkspaceSetupIsReportedAsFailure(@TempDir Path tmp) throws IOException {
        // A regular file where a directory is required forces createDirectories to fail.
        Path blocker = Files.createFile(tmp.resolve("blocker"));

        ToolResponse res = tool.execute(ctx("Goal", blocker.resolve("nested").toString()), Map.of("code", "echo hi"));

        assertFalse(res.success(), res.output());
        assertEquals(1, res.exitCode());
        assertFalse(res.output().isBlank());
    }

    @Test
    void javaCompilationFailureIsReportedAsFailure() throws IOException {
        doThrow(new IOException("javac not found")).when(hostJavaCompiler).compile(any(), any());

        ToolResponse res = tool.execute(ctx("Goal", null), Map.of("code", "public class Main {}", "language", "java"));

        assertFalse(res.success(), res.output());
        assertEquals(1, res.exitCode());
        assertTrue(res.output().contains("javac not found"), res.output());
    }

    // ── Browser-preview planning (pure) ──────────────────────────────────────────────────

    @Test
    void browserPreviewPlanFallsBackForMissingContextOrCode() {
        assertNull(tool.rewriteBrowserPreviewIfNeeded(null, "cat x.html").targetPath());
        assertNull(tool.rewriteBrowserPreviewIfNeeded(ctx(BROWSER_GOAL, null), null)
                .targetPath());
        assertNull(tool.rewriteBrowserPreviewIfNeeded(ctx(BROWSER_GOAL, null), "   ")
                .targetPath());
    }

    @Test
    void browserPreviewPlanSkipsCodeThatAlreadyOpensABrowser() {
        assertNull(tool.rewriteBrowserPreviewIfNeeded(ctx(BROWSER_GOAL, null), "open report.html")
                .targetPath());
    }

    @Test
    void browserPreviewPlanSkipsNonInspectionCode() {
        assertNull(tool.rewriteBrowserPreviewIfNeeded(ctx(BROWSER_GOAL, null), "echo hello")
                .targetPath());
    }

    @Test
    void browserPreviewPlanRewritesHtmlInspectionIntoOpenerScript() {
        var plan = tool.rewriteBrowserPreviewIfNeeded(ctx(BROWSER_GOAL, null), "cat report.html");

        assertEquals("report.html", plan.targetPath());
        assertTrue(plan.code().contains("report.html"), plan.code());
    }

    // ── Artifact snapshot / auto-open ────────────────────────────────────────────────────

    /**
     * An HTML artifact that already existed before the run must not be re-opened. This pins the
     * snapshot/compare path without ever spawning a browser opener.
     */
    @Test
    void preExistingHtmlArtifactIsNotAutoOpened(@TempDir Path skillHome) throws IOException {
        Files.writeString(Files.createDirectories(skillHome.resolve("output")).resolve("report.html"), "<html/>");

        ToolResponse res = tool.execute(ctx("Goal", skillHome.toString(), true), Map.of("code", "echo hi"));

        assertTrue(res.success(), res.output());
        assertTrue(res.output().contains("no new HTML artifact was detected"), res.output());
    }

    // ── Browser-artifact mirroring ───────────────────────────────────────────────────────

    @Test
    void browserPreviewTargetIsReportedWhenSkillHomeIsUnset(@TempDir Path tmp) {
        tool.workspaceRootDir = tmp.toString();

        ToolResponse res = tool.execute(ctx(BROWSER_GOAL, null), Map.of("code", "cat report.html"));

        assertTrue(res.success(), res.output());
        assertTrue(res.output().contains("report.html"), res.output());
    }

    @Test
    void browserPreviewMirrorIsSkippedWhenTargetDoesNotExist(@TempDir Path skillHome) {
        ToolResponse res = tool.execute(ctx(BROWSER_GOAL, skillHome.toString()), Map.of("code", "cat missing.html"));

        assertTrue(res.success(), res.output());
        assertTrue(res.output().contains("missing.html"), res.output());
    }

    /** A target that already lives in the skill's output dir is returned as-is, not re-copied. */
    @Test
    void browserPreviewMirrorReturnsSourceAlreadyInOutputDir(@TempDir Path skillHome) throws IOException {
        Path artifact = Files.writeString(
                Files.createDirectories(skillHome.resolve("output")).resolve("already.html"), "<html/>");

        ToolResponse res = tool.execute(ctx(BROWSER_GOAL, skillHome.toString()), Map.of("code", "cat " + artifact));

        assertTrue(res.success(), res.output());
        assertTrue(res.output().contains(artifact.toString()), res.output());
    }
}
