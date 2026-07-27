package com.amsha.core.guardrail;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import jakarta.enterprise.inject.Instance;

import java.lang.reflect.Field;
import java.util.List;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GuardrailCoordinatorTest {

    GuardrailCoordinator coordinator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        coordinator = new GuardrailCoordinator();
        var tracer = mock(Tracer.class);
        var spanBuilder = mock(SpanBuilder.class);
        var span = mock(Span.class);
        lenient().when(tracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        lenient().when(spanBuilder.setAttribute(anyString(), any())).thenReturn(spanBuilder);
        lenient().when(spanBuilder.startSpan()).thenReturn(span);
        var input = (Instance<PreLlmGuard>) mock(Instance.class);
        var output = (Instance<PostLlmGuard>) mock(Instance.class);
        // Stub forEach to do nothing by default; tests override via setField
        doAnswer(inv -> null).when(input).forEach(any());
        doAnswer(inv -> null).when(output).forEach(any());
        field("inputGuards").set(coordinator, input);
        field("outputGuards").set(coordinator, output);
        field("auditService").set(coordinator, mock(AuditService.class));
        field("tracer").set(coordinator, tracer);
    }

    private static Field field(String name) {
        try {
            var f = GuardrailCoordinator.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private void inputGuards(PreLlmGuard... guards) throws Exception {
        List<PreLlmGuard> list = List.of(guards);
        var inst = (Instance<PreLlmGuard>) field("inputGuards").get(coordinator);
        doAnswer(inv -> {
                    list.forEach(inv.getArgument(0));
                    return null;
                })
                .when(inst)
                .forEach(any());
    }

    @SuppressWarnings("unchecked")
    private void outputGuards(PostLlmGuard... guards) throws Exception {
        List<PostLlmGuard> list = List.of(guards);
        var inst = (Instance<PostLlmGuard>) field("outputGuards").get(coordinator);
        doAnswer(inv -> {
                    list.forEach(inv.getArgument(0));
                    return null;
                })
                .when(inst)
                .forEach(any());
    }

    private AuditService audit() throws Exception {
        return (AuditService) field("auditService").get(coordinator);
    }

    @Test
    void allInputAllowed() throws Exception {
        inputGuards((s, u, m, p) -> new GuardDecision.Allow(), (s, u, m, p) -> new GuardDecision.Allow());
        var r = coordinator.runInputGuards("s", "u", "m", "prompt");
        assertTrue(r.allowed());
        assertEquals("prompt", r.sanitizedPrompt());
    }

    @Test
    void inputBlocked() throws Exception {
        inputGuards((s, u, m, p) -> new GuardDecision.Block("bad", "detail"));
        var r = coordinator.runInputGuards("s", "u", "m", "prompt");
        assertFalse(r.allowed());
        assertFalse(r.retryable());
        assertEquals("bad", r.blockReason());
    }

    @Test
    void inputRetry() throws Exception {
        inputGuards((s, u, m, p) -> new GuardDecision.Retry("retry", "corrected"));
        var r = coordinator.runInputGuards("s", "u", "m", "prompt");
        assertFalse(r.allowed());
        assertTrue(r.retryable());
        assertEquals("corrected", r.retryPrompt());
    }

    @Test
    void inputSanitize() throws Exception {
        inputGuards((s, u, m, p) -> new GuardDecision.Sanitize("clean"), (s, u, m, p) -> new GuardDecision.Allow());
        var r = coordinator.runInputGuards("s", "u", "m", "raw");
        assertTrue(r.allowed());
        assertEquals("clean", r.sanitizedPrompt());
    }

    @Test
    void inputException() throws Exception {
        inputGuards((s, u, m, p) -> {
            throw new RuntimeException("oops");
        });
        var r = coordinator.runInputGuards("s", "u", "m", "prompt");
        assertFalse(r.allowed());
        assertTrue(r.blockReason().contains("Guardrail error"));
    }

    @Test
    void allOutputAllowed() throws Exception {
        outputGuards((s, u, m, r) -> new GuardDecision.Allow(), (s, u, m, r) -> new GuardDecision.Allow());
        var r = coordinator.runOutputGuards("s", "u", "m", "resp");
        assertTrue(r.allowed());
        assertEquals("resp", r.sanitizedResponse());
    }

    @Test
    void outputBlocked() throws Exception {
        outputGuards((s, u, m, r) -> new GuardDecision.Block("bad", "x"));
        var r = coordinator.runOutputGuards("s", "u", "m", "resp");
        assertFalse(r.allowed());
        assertEquals("bad", r.blockReason());
    }

    @Test
    void outputRetry() throws Exception {
        outputGuards((s, u, m, r) -> new GuardDecision.Retry("r", "c"));
        var r = coordinator.runOutputGuards("s", "u", "m", "resp");
        assertFalse(r.allowed());
        assertTrue(r.retryable());
        assertEquals("c", r.retryResponse());
    }

    @Test
    void outputSanitize() throws Exception {
        outputGuards((s, u, m, r) -> new GuardDecision.Sanitize("clean"));
        var r = coordinator.runOutputGuards("s", "u", "m", "resp");
        assertTrue(r.allowed());
        assertEquals("clean", r.sanitizedResponse());
    }

    @Test
    void outputException() throws Exception {
        outputGuards((s, u, m, r) -> {
            throw new RuntimeException("oops");
        });
        var r = coordinator.runOutputGuards("s", "u", "m", "resp");
        assertFalse(r.allowed());
        assertTrue(r.blockReason().contains("Guardrail error"));
    }

    @Test
    void inputResultFields() {
        var a = GuardrailCoordinator.InputResult.allowed("p");
        assertTrue(a.allowed());
        assertEquals("p", a.sanitizedPrompt());
        var b = GuardrailCoordinator.InputResult.blocked("no");
        assertFalse(b.allowed());
        assertEquals("no", b.blockReason());
        var c = GuardrailCoordinator.InputResult.retry("r", "c");
        assertFalse(c.allowed());
        assertTrue(c.retryable());
        assertEquals("c", c.retryPrompt());
    }

    @Test
    void outputResultFields() {
        var a = GuardrailCoordinator.OutputResult.allowed("p");
        assertTrue(a.allowed());
        assertEquals("p", a.sanitizedResponse());
        var b = GuardrailCoordinator.OutputResult.blocked("no");
        assertFalse(b.allowed());
        assertEquals("no", b.blockReason());
        var c = GuardrailCoordinator.OutputResult.retry("r", "c");
        assertFalse(c.allowed());
        assertTrue(c.retryable());
        assertEquals("c", c.retryResponse());
    }

    @Test
    void inputAuditRecorded() throws Exception {
        inputGuards((s, u, m, p) -> new GuardDecision.Allow());
        coordinator.runInputGuards("s", "u", "m", "p");
        verify(audit())
                .record(anyString(), anyString(), eq("guardrail"), eq(SecurityEventType.REFLEX_COMMAND), anyString());
    }

    @Test
    void outputAuditRecorded() throws Exception {
        outputGuards((s, u, m, r) -> new GuardDecision.Allow());
        coordinator.runOutputGuards("s", "u", "m", "r");
        verify(audit())
                .record(anyString(), anyString(), eq("guardrail"), eq(SecurityEventType.REFLEX_COMMAND), anyString());
    }
}
