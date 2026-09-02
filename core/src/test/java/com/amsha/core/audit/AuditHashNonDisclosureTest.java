package com.amsha.core.audit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.Arrays;

import com.amsha.core.agent.trace.AgentTraceEvent;
import com.amsha.core.agent.trace.StationCode;
import com.amsha.core.agent.trace.TracePublisher;
import com.amsha.core.agent.trace.TraceRedactor;
import com.amsha.core.agent.trace.TraceStage;

import org.junit.jupiter.api.Test;

/**
 * Issue 03 AC3 / AC4 — audit-hash non-disclosure: the audit hash may be shown, but the key must
 * never be serialized into a trace or sent to the client.
 *
 * <p>Defence in depth, both sides of which are pinned here:
 * <ol>
 *   <li>{@link AuditHashUtil} never hands the HMAC key out (it is private and only ever cloned
 *       internally into {@code Mac.init}), so the key cannot reach a trace by construction.</li>
 *   <li>{@link TraceRedactor} strips any key-shaped value that somehow reaches a free-text field,
 *       server-side and before publication.</li>
 * </ol>
 */
class AuditHashNonDisclosureTest {

    private final TraceRedactor redactor = new TraceRedactor();

    /** Representative key material — the shape matters, not the value. */
    private static final String KEY_MATERIAL = "7f3ac91de44b2c085e6a1d9f0b83c25e";

    /** A SHA-256 / HMAC hex digest — safe to display and safe to persist. */
    private static final String HASH_DIGEST = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

    @Test
    void auditKeyMaterialIsNeverSerializedIntoATrace() {
        AgentTraceEvent event = AgentTraceEvent.of(
                        TracePublisher.runId("s1"), "s1", TracePublisher.DEFAULT_TURN_NUMBER, 0, TraceStage.GUARD_CHECK)
                .withStation(StationCode.STATION_05_TOOL_GUARD)
                .withStatus("DENIED")
                .withDetail("audit_hmac_key=" + KEY_MATERIAL);

        AgentTraceEvent redacted = redactor.redact(event);

        assertFalse(redacted.detail().contains(KEY_MATERIAL), "audit key material must never reach the client");
        assertTrue(redacted.detail().contains(TraceRedactor.REDACTED), "the withheld value must be marked");
    }

    @Test
    void keyMaterialIsRedactedAcrossEveryKeyShapedField() {
        assertFalse(redactor.redactText("hmac_key = " + KEY_MATERIAL, 512).contains(KEY_MATERIAL));
        assertFalse(redactor.redactText("audit_key: " + KEY_MATERIAL, 512).contains(KEY_MATERIAL));
        assertFalse(redactor.redactText("signing_key=" + KEY_MATERIAL, 512).contains(KEY_MATERIAL));
        assertFalse(redactor.redactText("audit_hmac_key = \"" + KEY_MATERIAL + "\"", 512)
                .contains(KEY_MATERIAL));
    }

    @Test
    void auditHashItselfMayStillBeDisplayed() {
        // The contract allows the hash; only the key is withheld.
        AgentTraceEvent event = AgentTraceEvent.of(
                        TracePublisher.runId("s1"), "s1", TracePublisher.DEFAULT_TURN_NUMBER, 0, TraceStage.TURN_END)
                .withStation(StationCode.STATION_10_OUTBOUND)
                .withStatus("END")
                .withDetail("auditHash=" + HASH_DIGEST);

        AgentTraceEvent redacted = redactor.redact(event);

        assertTrue(redacted.detail().contains(HASH_DIGEST), "hash digests are safe to display");
    }

    @Test
    void noRawDisclosureByDefaultForOtherClassifiedMaterial() {
        // AC4 — prompts, completions, authorization headers and provider keys are all withheld.
        assertFalse(
                redactor.redactText("prompt: You are a helpful assistant", 512).contains("helpful assistant"));
        assertFalse(redactor.redactText("completion: the secret answer", 512).contains("secret answer"));
        assertFalse(
                redactor.redactText("authorization: Bearer abc.def.ghi", 512).contains("abc.def.ghi"));
        assertFalse(redactor.redactText("api_key=AKIAIOSFODNN7EXAMPLE", 512).contains("AKIAIOSFODNN7EXAMPLE"));
    }

    @Test
    void auditHashUtilExposesOnlyHexDigests() {
        // Structural check: the class surfaces digests, never key material or a key accessor.
        Arrays.stream(AuditHashUtil.class.getDeclaredMethods())
                .filter(m -> m.getName().toLowerCase().contains("key"))
                .forEach(m -> assertFalse(
                        Modifier.isPublic(m.getModifiers()),
                        "AuditHashUtil must not expose '" + m.getName() + "' publicly"));
    }
}
