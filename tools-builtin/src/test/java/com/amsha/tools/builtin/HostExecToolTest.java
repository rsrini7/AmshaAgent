package com.amsha.tools.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.common.util.HtmlArtifactUtil;
import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.audit.AuditService;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.tools.ToolExecutionContext;
import com.amsha.core.tools.ToolResponse;
import com.amsha.sandbox.DirectExecutor;
import com.amsha.sandbox.ToolExecutionException;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.ToolOperation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("java:S1192")
class HostExecToolTest {

    @Mock
    DirectExecutor mockDirectExecutor;

    @Mock
    AuditService mockAuditService;

    @Mock
    HostJavaCompiler mockHostJavaCompiler;

    @InjectMocks
    HostExecTool tool;

    @BeforeEach
    void setUp() {
        tool.init();
    }

    private static final String TRUNCATION_SUFFIX = "[output truncated at max output chars limit]";

    // -------------------------------------------------------------------------
    // Capability / schema tests
    // -------------------------------------------------------------------------

    @Test
    void name_returns_host_exec_capability() {
        assertEquals(ToolCapability.HOST_EXEC.toolName(), tool.name());
        assertEquals("host_exec", tool.name());
    }

    @Test
    void required_operation_is_execute_shell() {
        assertEquals(ToolOperation.EXECUTE_SHELL, tool.requiredOperation());
    }

    @Test
    void requires_sandbox_is_false() {
        assertFalse(tool.requiresSandbox(), "host_exec must opt out of Podman sandbox");
    }

    @Test
    void capability_returns_host_exec() {
        assertEquals(ToolCapability.HOST_EXEC, tool.capability());
    }

    @Test
    void parameters_schema_requires_code() {
        var schema = tool.parametersSchema();
        assertNotNull(schema);
        assertEquals(List.of("code"), schema.required());
        assertTrue(schema.properties().containsKey("code"));
        assertTrue(schema.properties().containsKey("language"));
    }

    @Test
    void description_mentions_host_execution() {
        String desc = tool.description();
        assertNotNull(desc);
        assertTrue(desc.toLowerCase().contains("host"), "description must mention host: " + desc);
    }

    @Test
    void description_includes_generic_host_exec_diagnostics_guidance() {
        String desc = tool.description().toLowerCase(Locale.ROOT);
        assertTrue(
                desc.contains("bypassing the podman sandbox")
                        && desc.contains("ps -axww")
                        && (desc.contains("lsof") || desc.contains("ss -ltnp") || desc.contains("jcmd")),
                "description must include generic host_exec diagnostics guidance: " + desc);
    }

    // -------------------------------------------------------------------------
    // Bash execution
    // -------------------------------------------------------------------------

    @Test
    void bash_execution_invokes_direct_executor_and_returns_stdout() throws Exception {
        when(mockDirectExecutor.executeDirectly(any(), anyList(), any())).thenReturn("hello world\n");

        AtomicReference<ToolResponse> ref = new AtomicReference<>();
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "debugger", () -> {
            ref.set(tool.execute(ctx(), Map.of("code", "echo hello world", "language", "bash")));
            return null;
        });

        ToolResponse r = ref.get();
        assertTrue(r.success(), "bash execution must succeed: " + r.output());
        assertEquals("hello world\n", r.output());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
        verify(mockDirectExecutor, times(1)).executeDirectly(any(), cmdCaptor.capture(), any());

        List<String> cmd = cmdCaptor.getValue();
        assertEquals("bash", cmd.get(0), "first token must be the bash interpreter");
        assertTrue(cmd.get(1).endsWith("script.sh"), "second token must point to <workspace>/script.sh");
    }

    @Test
    void bash_language_lookup_is_case_insensitive() throws Exception {
        when(mockDirectExecutor.executeDirectly(any(), anyList(), any())).thenReturn("ok\n");

        AtomicReference<ToolResponse> ref = new AtomicReference<>();
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "debugger", () -> {
            ref.set(tool.execute(ctx(), Map.of("code", "echo ok", "language", "BASH")));
            return null;
        });

        assertTrue(
                ref.get().success(),
                "BASH (uppercase) must be accepted: " + ref.get().output());
        verify(mockDirectExecutor, times(1)).executeDirectly(any(), anyList(), any());
    }

    @Test
    void bash_secrets_are_never_passed_to_direct_executor() throws Exception {
        when(mockDirectExecutor.executeDirectly(any(), anyList(), any())).thenReturn("ok\n");

        AtomicReference<ToolResponse> ref = new AtomicReference<>();
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "debugger", () -> {
            ref.set(tool.execute(ctx(), Map.of("code", "echo ok", "language", "bash")));
            return null;
        });

        assertTrue(ref.get().success());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, byte[]>> secretsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(mockDirectExecutor).executeDirectly(any(), anyList(), secretsCaptor.capture());
        assertTrue(secretsCaptor.getValue().isEmpty(), "host_exec must not propagate secrets");
    }

    // -------------------------------------------------------------------------
    // Java execution
    // -------------------------------------------------------------------------

    @Test
    void java_execution_compiles_and_runs_class() throws Exception {
        when(mockDirectExecutor.executeDirectly(any(), anyList(), any())).thenReturn("Hi from Java\n");

        AtomicReference<ToolResponse> ref = new AtomicReference<>();
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "debugger", () -> {
            String code = "public class Greet { public static void main(String[] a) { "
                    + "System.out.println(\"Hi from Java\"); } }";
            ref.set(tool.execute(ctx(), Map.of("code", code, "language", "java")));
            return null;
        });

        ToolResponse r = ref.get();
        assertTrue(r.success(), "java execution must succeed: " + r.output());
        assertEquals("Hi from Java\n", r.output());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
        verify(mockDirectExecutor, times(1)).executeDirectly(any(), cmdCaptor.capture(), any());

        List<String> cmd = cmdCaptor.getValue();
        // Expected: [java, -cp, <workspace>, Greet]
        assertEquals("java", cmd.get(0));
        assertEquals("-cp", cmd.get(1));
        assertEquals("Greet", cmd.get(3), "class name must come from extracted public class");
    }

    @Test
    void java_execution_wraps_bare_snippet_in_main_class() throws Exception {
        when(mockDirectExecutor.executeDirectly(any(), anyList(), any())).thenReturn("42\n");

        AtomicReference<ToolResponse> ref = new AtomicReference<>();
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "debugger", () -> {
            // No class declaration - tool should wrap it.
            ref.set(tool.execute(ctx(), Map.of("code", "System.out.println(42);", "language", "java")));
            return null;
        });

        assertTrue(
                ref.get().success(),
                "bare snippet must be wrapped and run: " + ref.get().output());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
        verify(mockDirectExecutor).executeDirectly(any(), cmdCaptor.capture(), any());
        assertEquals("Main", cmdCaptor.getValue().get(3));
    }

    // -------------------------------------------------------------------------
    // Unsupported language
    // -------------------------------------------------------------------------

    @Test
    void unsupported_language_returns_failure_without_invoking_direct_executor() throws Exception {
        AtomicReference<ToolResponse> ref = new AtomicReference<>();
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "debugger", () -> {
            ref.set(tool.execute(ctx(), Map.of("code", "puts 'hi'", "language", "ruby")));
            return null;
        });

        ToolResponse r = ref.get();
        assertFalse(r.success());
        assertTrue(r.output().contains("language not supported"), "Actual: " + r.output());
        assertTrue(r.output().contains("ruby"), "Actual: " + r.output());
        verify(mockDirectExecutor, never()).executeDirectly(any(), anyList(), any());
    }

    // -------------------------------------------------------------------------
    // Parameter validation
    // -------------------------------------------------------------------------

    @Test
    void missing_language_defaults_to_bash_and_succeeds() throws Exception {
        when(mockDirectExecutor.executeDirectly(any(), anyList(), any())).thenReturn("hi\n");

        AtomicReference<ToolResponse> ref = new AtomicReference<>();
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "debugger", () -> {
            ref.set(tool.execute(ctx(), Map.of("code", "echo hi")));
            return null;
        });

        ToolResponse r = ref.get();
        assertTrue(r.success());
        assertEquals("hi\n", r.output());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
        verify(mockDirectExecutor).executeDirectly(any(), cmdCaptor.capture(), any());
        List<String> cmd = cmdCaptor.getValue();
        assertEquals("bash", cmd.get(0));
        assertTrue(cmd.get(1).endsWith("script.sh"));
    }

    @Test
    void missing_code_returns_failure() throws Exception {
        AtomicReference<ToolResponse> ref = new AtomicReference<>();
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "debugger", () -> {
            ref.set(tool.execute(ctx(), Map.of("language", "bash")));
            return null;
        });

        ToolResponse r = ref.get();
        assertFalse(r.success());
        assertTrue(r.output().contains("'code' is required"), "Actual: " + r.output());
        verify(mockDirectExecutor, never()).executeDirectly(any(), anyList(), any());
    }

    // -------------------------------------------------------------------------
    // Error / exception handling
    // -------------------------------------------------------------------------

    @Test
    void tool_execution_exception_is_returned_as_failure_with_exit_code() throws Exception {
        when(mockDirectExecutor.executeDirectly(any(), anyList(), any()))
                .thenThrow(new ToolExecutionException("exit 7: boom", 7));

        AtomicReference<ToolResponse> ref = new AtomicReference<>();
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "debugger", () -> {
            ref.set(tool.execute(ctx(), Map.of("code", "exit 7", "language", "bash")));
            return null;
        });

        ToolResponse r = ref.get();
        assertFalse(r.success());
        assertEquals(7, r.exitCode());
        assertTrue(r.output().contains("exit 7"), "Actual: " + r.output());
    }

    @Test
    void timeout_security_exception_is_returned_as_infra_failure() throws Exception {
        when(mockDirectExecutor.executeDirectly(any(), anyList(), any()))
                .thenThrow(new SecurityException("Tool timed out after 30s"));

        AtomicReference<ToolResponse> ref = new AtomicReference<>();
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "debugger", () -> {
            ref.set(tool.execute(ctx(), Map.of("code", "sleep 999", "language", "bash")));
            return null;
        });

        ToolResponse r = ref.get();
        assertTrue(r.isInfraFailure(), "timeout must be flagged as infra failure");
        assertFalse(r.success());
        assertTrue(r.output().contains("HOST_EXEC_OFFLINE"), "Actual: " + r.output());
    }

    @Test
    void runtime_exception_is_returned_as_failure_without_throwing() throws Exception {
        when(mockDirectExecutor.executeDirectly(any(), anyList(), any())).thenThrow(new RuntimeException("boom"));

        AtomicReference<ToolResponse> ref = new AtomicReference<>();
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "debugger", () -> {
            ref.set(tool.execute(ctx(), Map.of("code", "x", "language", "bash")));
            return null;
        });

        ToolResponse r = ref.get();
        assertFalse(r.success());
        assertTrue(r.output().contains("boom"), "Actual: " + r.output());
    }

    // -------------------------------------------------------------------------
    // Output truncation
    // -------------------------------------------------------------------------

    @Test
    void output_over_8192_chars_is_truncated() throws Exception {
        String bigOutput = "C".repeat(10_000);
        when(mockDirectExecutor.executeDirectly(any(), anyList(), any())).thenReturn(bigOutput);

        AtomicReference<ToolResponse> ref = new AtomicReference<>();
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "debugger", () -> {
            ref.set(tool.execute(ctx(), Map.of("code", "echo", "language", "bash")));
            return null;
        });

        ToolResponse r = ref.get();
        assertEquals(8192 + TRUNCATION_SUFFIX.length(), r.output().length());
        assertTrue(r.output().endsWith(TRUNCATION_SUFFIX));
    }

    @Test
    void templateReads_do_not_collapse_into_artifact_notice() {
        String html = """
                <html>
                  <body><main>template</main></body>
                </html>
                """;

        String formatted =
                tool.postProcessOutput(html, Map.of("code", "cat /tmp/skills/example/templates/viewer.html"));

        assertEquals(HtmlArtifactUtil.HTML_TEMPLATE_NOTICE, formatted);
    }

    @Test
    void outputFolder_html_collapses_into_artifact_notice() {
        String html = """
                <html>
                  <body><main>artifact</main></body>
                </html>
                """;

        String formatted = tool.postProcessOutput(html, Map.of("code", "cat output/generated.html"));

        assertEquals(HtmlArtifactUtil.HTML_ARTIFACT_NOTICE, formatted);
    }

    @Test
    void skillHome_browser_artifact_is_mirrored_into_skill_output_dir() throws Exception {
        Path tempDir = Files.createTempDirectory("host-exec-test-");
        Path skillHome = Files.createTempDirectory(tempDir, "skill-home");
        Path source = Files.createTempFile(tempDir, "particle-art", ".html");
        Files.writeString(source, """
                <!doctype html>
                <html>
                  <body><main>artifact</main></body>
                </html>
                """);

        when(mockDirectExecutor.executeDirectly(any(), anyList(), any())).thenReturn("ignored\n");

        AtomicReference<ToolResponse> ref = new AtomicReference<>();
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "debugger", () -> {
            ref.set(tool.execute(
                    ctx(
                            "use algorithmic art to generate very simple particle system open in browser",
                            skillHome.toString()),
                    Map.of("code", "cat " + source, "language", "bash")));
            return null;
        });

        ToolResponse r = ref.get();
        assertTrue(r.success(), "browser preview should succeed: " + r.output());

        Path mirrored = skillHome.resolve("output").resolve(source.getFileName());
        assertTrue(Files.exists(mirrored), "mirrored artifact missing: " + mirrored);
        assertEquals(Files.readString(source), Files.readString(mirrored));
        assertEquals("Opened browser preview: " + mirrored, r.output());
    }

    private ToolExecutionContext ctx() {
        return ctx(null);
    }

    private ToolExecutionContext ctx(String rawGoal) {
        return ctx(rawGoal, null);
    }

    private ToolExecutionContext ctx(String rawGoal, String skillHome) {
        return new ToolExecutionContext(
                "test-session",
                "user1",
                "chat1",
                "debugger",
                AutonomyLevel.FULL,
                "trace-1",
                null,
                null,
                rawGoal,
                null,
                null,
                null,
                skillHome);
    }
}
