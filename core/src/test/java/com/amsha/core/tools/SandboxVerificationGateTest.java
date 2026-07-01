package com.amsha.core.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SandboxVerificationGateTest {

    @Mock
    SandboxExecTool sandboxExecTool;

    @InjectMocks
    SandboxVerificationGate gate;

    @Mock
    ToolExecutionContext ctx;

    @Test
    void verify_emptyOutput_returnsSuccess() {
        var res = gate.verify(ctx, "");
        assertTrue(res.success());
    }

    @Test
    void verify_nullOutput_returnsSuccess() {
        var res = gate.verify(ctx, null);
        assertTrue(res.success());
    }

    @Test
    void verify_nonExecutableLang_returnsSuccessWithoutCallingSandbox() {
        var res = gate.verify(ctx, "```markdown\n# Hello World\n```");
        assertTrue(res.success());
        verify(sandboxExecTool, never()).execute(any(), any());
    }

    @Test
    void verify_unlabeledBlock_skipsExecution() {
        var res = gate.verify(ctx, "```\nsome code\n```");
        assertTrue(res.success());
        verify(sandboxExecTool, never()).execute(any(), any());
    }

    @Test
    void verify_successfulPythonBlock_returnsSuccess() {
        when(sandboxExecTool.execute(any(), any())).thenReturn(ToolResponse.success("Connection Success"));

        var res = gate.verify(ctx, "Some text before\n```python\nprint('hello')\n```\nSome text after");
        assertTrue(res.success());
        verify(sandboxExecTool).execute(any(), eq(Map.of("code", "print('hello')", "language", "python")));
    }

    @Test
    void verify_failedPythonBlock_returnsFailure() {
        when(sandboxExecTool.execute(any(), any()))
                .thenReturn(ToolResponse.failure(1, "SyntaxError: EOL while scanning string literal"));

        var res = gate.verify(ctx, "```python\nprint('hello\n```");
        assertFalse(res.success());
        assertTrue(res.errorOutput().contains("SyntaxError"));
    }

    @Test
    void verify_bashBlock_executesBash() {
        when(sandboxExecTool.execute(any(), any())).thenReturn(ToolResponse.success("echo output"));

        var res = gate.verify(ctx, "```bash\necho 123\n```");
        assertTrue(res.success());
        verify(sandboxExecTool).execute(any(), eq(Map.of("code", "echo 123", "language", "bash")));
    }

    @Test
    void verify_javaBlock_executesJava() {
        when(sandboxExecTool.execute(any(), any())).thenReturn(ToolResponse.success("java success"));

        var res = gate.verify(ctx, "```java\nSystem.out.println(1);\n```");
        assertTrue(res.success());
        verify(sandboxExecTool).execute(any(), eq(Map.of("code", "System.out.println(1);", "language", "java")));
    }

    @Test
    void verify_sandboxException_handlesCleanly() {
        when(sandboxExecTool.execute(any(), any())).thenThrow(new RuntimeException("Podman connection refused"));

        var res = gate.verify(ctx, "```python\nprint(1)\n```");
        assertFalse(res.success());
        assertTrue(res.errorOutput().contains("Podman connection refused"));
    }

    @Test
    void verify_multipleBlocks_returnsFirstFailure() {
        // First block passes, second fails
        when(sandboxExecTool.execute(any(), any()))
                .thenReturn(ToolResponse.success("pass"))
                .thenReturn(ToolResponse.failure(1, "fail"));

        var res = gate.verify(ctx, "```python\nprint(1)\n```\n```python\nprint(2)\n```");
        assertFalse(res.success());
        assertTrue(res.errorOutput().contains("fail"));
        verify(sandboxExecTool, times(2)).execute(any(), any());
    }
}
