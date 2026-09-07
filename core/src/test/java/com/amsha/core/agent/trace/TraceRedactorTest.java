package com.amsha.core.agent.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Redaction tests for {@link TraceRedactor}.
 *
 * <p>These are the primary information-disclosure guard for the cockpit: everything the UI can
 * render has passed through this redactor, so a leak here is a leak in the product.
 */
class TraceRedactorTest {

    private final TraceRedactor redactor = new TraceRedactor();

    private static AgentTraceEvent eventWithDetail(String detail) {
        return AgentTraceEvent.of("run-1", "session-1", 1, 0, TraceStage.LLM_REASONING)
                .withDetail(detail);
    }

    private static String redact(String detail) {
        return new TraceRedactor().redact(eventWithDetail(detail)).detail();
    }

    @Test
    void removesRawPromptAndCompletion() {
        String output = redact("system_prompt: You are a secret agent. completion: the answer is 42");

        assertFalse(output.contains("You are a secret agent"), output);
        assertFalse(output.contains("the answer is 42"), output);
        assertTrue(output.contains(TraceRedactor.REDACTED), output);
    }

    @Test
    void removesRoleLabelledMessages() {
        String output = redact("user_message: my bank password is hunter2");

        assertFalse(output.contains("hunter2"), output);
        assertTrue(output.contains(TraceRedactor.REDACTED), output);
    }

    @Test
    void removesAuthorizationHeadersAndBearerTokens() {
        String output = redact("Authorization: Bearer sk-abcdefghijklmnopqrstuvwx");

        assertFalse(output.contains("sk-abcdefghijklmnopqrstuvwx"), output);
        assertTrue(output.contains(TraceRedactor.REDACTED), output);
    }

    @Test
    void removesAmshaApiKeyHeader() {
        String output = redact("X-AMSHA-API-KEY: owner-key-1234567890");

        assertFalse(output.contains("owner-key-1234567890"), output);
        assertTrue(output.contains(TraceRedactor.REDACTED), output);
    }

    @Test
    void removesSecretsPasswordsAndTokens() {
        String output = redact("api_key: AAAABBBBCCCCDDDD password: p@ssw0rd access_token: tok-9999");

        assertFalse(output.contains("AAAABBBBCCCCDDDD"), output);
        assertFalse(output.contains("p@ssw0rd"), output);
        assertFalse(output.contains("tok-9999"), output);
    }

    @Test
    void removesAuditKeys() {
        String output = redact("audit_key: 9f8e7d6c5b4a3210");

        assertFalse(output.contains("9f8e7d6c5b4a3210"), output);
        assertTrue(output.contains(TraceRedactor.REDACTED), output);
    }

    @Test
    void removesArbitraryToolParameters() {
        String output = redact("params: {\"command\": \"rm -rf /tmp/secret\"}");

        assertFalse(output.contains("rm -rf /tmp/secret"), output);
        assertTrue(output.contains(TraceRedactor.REDACTED), output);
    }

    @Test
    void removesOpenAiStyleKeyLiterals() {
        String output = redact("provider key sk-proj-abcdefghijklmnopqrstuv rejected");

        assertFalse(output.contains("sk-proj-abcdefghijklmnopqrstuv"), output);
        assertTrue(output.contains(TraceRedactor.REDACTED), output);
    }

    @Test
    void keepsOperatorSafeSummaryText() {
        String output = redact("guard evaluated execute_shell under SUPERVISED autonomy");

        assertEquals("guard evaluated execute_shell under SUPERVISED autonomy", output);
    }

    @Test
    void capsOversizedDetailToTheByteBudget() {
        String oversized = "a".repeat(TraceRedactor.MAX_DETAIL_BYTES * 4);
        String output = redact(oversized);

        assertTrue(
                output.getBytes(StandardCharsets.UTF_8).length <= TraceRedactor.MAX_DETAIL_BYTES,
                "detail exceeded byte budget");
        assertTrue(output.endsWith(TraceRedactor.TRUNCATED), output);
    }

    @Test
    void neverSplitsAMultibyteCharacterWhenCapping() {
        String oversized = "é".repeat(TraceRedactor.MAX_DETAIL_BYTES * 2);
        String output = redact(oversized);

        assertTrue(output.getBytes(StandardCharsets.UTF_8).length <= TraceRedactor.MAX_DETAIL_BYTES);
        assertFalse(output.contains("�"), "capping produced a replacement character");
    }

    @Test
    void redactionIsDeterministic() {
        String input = "Authorization: Bearer sk-abcdefghijklmnopqrstuv params: {\"a\": 1}";

        assertEquals(redact(input), redact(input));
    }

    @Test
    void returnsARedactedCopyAndLeavesTheOriginalUntouched() {
        AgentTraceEvent original = eventWithDetail("password: hunter2");
        AgentTraceEvent redacted = redactor.redact(original);

        assertNotSame(original, redacted);
        assertEquals("password: hunter2", original.detail());
        assertFalse(redacted.detail().contains("hunter2"));
    }

    @Test
    void redactsStatusAndRiskLevelToo() {
        AgentTraceEvent redacted =
                redactor.redact(AgentTraceEvent.of("run-1", "session-1", 1, 0, TraceStage.GUARD_CHECK)
                        .withStatus("ALLOW secret: leakme")
                        .withRiskLevel("HIGH audit_key: abcdef"));

        assertFalse(redacted.status().contains("leakme"), redacted.status());
        assertFalse(redacted.riskLevel().contains("abcdef"), redacted.riskLevel());
    }

    @Test
    void preservesStructureAndIdentityFields() {
        AgentTraceEvent redacted = redactor.redact(sampleGuardEvent());

        assertEquals("event-42", redacted.eventId());
        assertEquals("run-7", redacted.runId());
        assertEquals("session-9", redacted.sessionId());
        assertEquals(TraceStage.GUARD_CHECK, redacted.stage());
        assertEquals(StationCode.STATION_05_TOOL_GUARD, redacted.station());
        assertEquals(new AgentTraceEvent.TraceMetrics(1, 2, 3L, 4, 5), redacted.metrics());
    }

    @Test
    void handlesNullInputAndNullFields() {
        assertNull(redactor.redact(null));
        assertNull(redactor.redactText(null, 16));
        assertNull(redactor.redact(AgentTraceEvent.of("run-1", "session-1", 1, 0, TraceStage.ROUTE))
                .detail());
    }

    private static AgentTraceEvent sampleGuardEvent() {
        return new AgentTraceEvent(
                "event-42",
                "run-7",
                "session-9",
                2,
                5L,
                TraceStage.GUARD_CHECK,
                StationCode.STATION_05_TOOL_GUARD,
                "ALLOW",
                "LOW",
                "allowed",
                new AgentTraceEvent.TraceMetrics(1, 2, 3L, 4, 5),
                Instant.parse("2026-09-01T10:15:30Z"));
    }
}
