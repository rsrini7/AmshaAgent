package com.arcclaw.core.structured;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.function.Supplier;

import jakarta.inject.Inject;

import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.audit.TelemetryAuditService;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.governance.GovernanceStrategy;
import com.arcclaw.core.observability.AgentOtelContext;
import io.opentelemetry.api.trace.Span;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class StructuredRetryLoopTest {

    @Inject
    StructuredRetryLoop retryLoop;

    @InjectMock
    GovernanceStrategy governanceLayer;

    @InjectMock
    TelemetryAuditService auditService;

    @InjectMock
    AgentOtelContext otelContext;

    private String sessionId;
    private Span mockSpan;

    @BeforeEach
    void setup() {
        sessionId = UUID.randomUUID().toString();
        mockSpan = mock(Span.class);
        when(otelContext.childSpan(anyString(), anyString())).thenReturn(mockSpan);
    }

    @Test
    void testSuccessOnFirstAttempt() {
        Supplier<String> call = () -> "ok";
        String result = retryLoop.execute(sessionId, "role", "op", call);
        assertEquals("ok", result);
        verify(mockSpan).end();
        verifyNoInteractions(auditService);
    }

    @Test
    void testSuccessOnRetry() {
        @SuppressWarnings("unchecked")
        Supplier<String> call = mock(Supplier.class);
        when(call.get())
            .thenThrow(new JsonExtractionException("fail", "raw", String.class, null, null))
            .thenReturn("recovered");

        String result = retryLoop.execute(sessionId, "role", "op", call);
        assertEquals("recovered", result);
        verify(auditService).record(eq(sessionId), anyString(), eq("role"), eq(SecurityEventType.STRUCTURED_OUTPUT_RETRY), anyString());
        verify(mockSpan).end();
    }

    @Test
    void testExhaustedRetriesThrows() {
        @SuppressWarnings("unchecked")
        Supplier<String> call = mock(Supplier.class);
        when(call.get()).thenThrow(new JsonExtractionException("fail", "raw", String.class, null, null));

        assertThrows(StructuredOutputException.class, () -> retryLoop.execute(sessionId, "role", "op", call));
        verify(mockSpan).end();
    }

    @Test
    void testBudgetExceededPropagates() {
        doThrow(new BudgetExceededException("exceeded")).when(governanceLayer).assertWithinBudget(sessionId);
        assertThrows(BudgetExceededException.class, () -> retryLoop.execute(sessionId, "role", "op", () -> "ok"));
    }

    @Test
    void testBudgetWarningProceeds() {
        @SuppressWarnings("unchecked")
        Supplier<String> call = mock(Supplier.class);
        when(call.get()).thenReturn("ok");

        String result = retryLoop.execute(sessionId, "role", "op", call);
        assertEquals("ok", result);
        verify(call, times(1)).get();
    }

    @Test
    void testNullSpanSafe() {
        when(otelContext.childSpan(anyString(), anyString())).thenReturn(null);
        String result = retryLoop.execute(sessionId, "role", "op", () -> "ok");
        assertEquals("ok", result);
    }

    @Test
    void testAuditOnlyOnSuccessAfterRetry() {
        @SuppressWarnings("unchecked")
        Supplier<String> call = mock(Supplier.class);
        when(call.get())
            .thenThrow(new JsonExtractionException("fail", "raw", String.class, null, null))
            .thenReturn("ok");

        retryLoop.execute(sessionId, "role", "op", call);
        verify(auditService, times(1)).record(any(), any(), any(), any(), any());
    }
}
