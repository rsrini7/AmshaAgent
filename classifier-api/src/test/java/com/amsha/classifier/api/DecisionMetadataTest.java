package com.amsha.classifier.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DecisionMetadata} — payload-free execution telemetry attached to an {@link
 * DecisionResult.Opinion}.
 *
 * <p>The contract is that every field is optional so absent provider telemetry is never replaced with a
 * synthetic number: an absent latency must stay absent, not become {@code 0}, and an absent token count must
 * stay absent, not become {@code -1}. The record enforces this with five "if present, then valid" guards.
 * Those guards were entirely unasserted — a guard could have been deleted, or inverted to reject a valid
 * value, without any test failing.
 */
class DecisionMetadataTest {

    @Test
    void emptyLeavesEveryFieldAbsent() {
        DecisionMetadata metadata = DecisionMetadata.empty();

        assertNull(metadata.providerId());
        assertNull(metadata.modelId());
        assertNull(metadata.latency());
        assertNull(metadata.inputTokens());
        assertNull(metadata.outputTokens());
    }

    @Test
    void acceptsAllFieldsPresent() {
        DecisionMetadata metadata = new DecisionMetadata("onnx-local", "jev-1.13.0", Duration.ofMillis(250), 12L, 7L);

        assertEquals("onnx-local", metadata.providerId());
        assertEquals("jev-1.13.0", metadata.modelId());
        assertEquals(Duration.ofMillis(250), metadata.latency());
        assertEquals(12L, metadata.inputTokens());
        assertEquals(7L, metadata.outputTokens());
    }

    @Test
    void acceptsZeroValuedTelemetryBecauseZeroIsAReportedNumberNotAnAbsence() {
        // The guards are "negative" checks, not "positive" checks: a genuine 0 ms / 0 token report is
        // admissible and must not be confused with the null that means "provider said nothing".
        DecisionMetadata metadata = new DecisionMetadata("p", "m", Duration.ZERO, 0L, 0L);

        assertEquals(Duration.ZERO, metadata.latency());
        assertEquals(0L, metadata.inputTokens());
        assertEquals(0L, metadata.outputTokens());
    }

    // ---------------------------------------------------------------- the five "if present, then valid" guards

    @Test
    void rejectsABlankProviderIdButAllowsAnAbsentOne() {
        assertTrue(
                assertThrows(IllegalArgumentException.class, () -> new DecisionMetadata("  ", null, null, null, null))
                        .getMessage()
                        .contains("providerId must not be blank if present"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new DecisionMetadata("", null, null, null, null))
                .getMessage()
                .contains("providerId must not be blank if present"));

        assertNull(new DecisionMetadata(null, null, null, null, null).providerId());
    }

    @Test
    void rejectsABlankModelIdButAllowsAnAbsentOne() {
        assertTrue(
                assertThrows(IllegalArgumentException.class, () -> new DecisionMetadata(null, "  ", null, null, null))
                        .getMessage()
                        .contains("modelId must not be blank if present"));

        assertNull(new DecisionMetadata(null, null, null, null, null).modelId());
    }

    @Test
    void rejectsANegativeLatency() {
        assertTrue(assertThrows(
                        IllegalArgumentException.class,
                        () -> new DecisionMetadata(null, null, Duration.ofMillis(-1), null, null))
                .getMessage()
                .contains("latency must not be negative"));
    }

    @Test
    void rejectsNegativeTokenCounts() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new DecisionMetadata(null, null, null, -1L, null))
                .getMessage()
                .contains("inputTokens must not be negative"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new DecisionMetadata(null, null, null, null, -1L))
                .getMessage()
                .contains("outputTokens must not be negative"));
    }

    // ---------------------------------------------------------------- toString

    @Test
    void toStringRendersAbsentFieldsAsSentinels() {
        assertEquals(
                "DecisionMetadata[provider=none, model=none, latencyMs=-1, inTokens=-1, outTokens=-1]",
                DecisionMetadata.empty().toString());
    }

    @Test
    void toStringRendersPresentFieldsAsReportedValues() {
        DecisionMetadata metadata = new DecisionMetadata("onnx-local", "jev-1.13.0", Duration.ofMillis(250), 12L, 7L);

        assertEquals(
                "DecisionMetadata[provider=onnx-local, model=jev-1.13.0, latencyMs=250, inTokens=12, outTokens=7]",
                metadata.toString());
    }

    @Test
    void toStringMixesPresentAndAbsentFieldsIndependently() {
        // Each of the five ternaries must fall back on its own, so a partially reported metadata record still
        // renders without inventing values for the fields the provider omitted.
        DecisionMetadata metadata = new DecisionMetadata("typesafe", null, null, 3L, null);

        assertEquals(
                "DecisionMetadata[provider=typesafe, model=none, latencyMs=-1, inTokens=3, outTokens=-1]",
                metadata.toString());
    }

    @Test
    void recordEqualityIsByComponent() {
        DecisionMetadata a = new DecisionMetadata("p", "m", Duration.ofMillis(5), 1L, 2L);
        DecisionMetadata same = new DecisionMetadata("p", "m", Duration.ofMillis(5), 1L, 2L);
        DecisionMetadata different = new DecisionMetadata("p", "m", Duration.ofMillis(5), 1L, 3L);

        assertEquals(a, same);
        assertEquals(a.hashCode(), same.hashCode());
        assertTrue(!a.equals(different));
        assertEquals(a, a);
        assertTrue(!a.equals(null));
        assertTrue(!a.equals("p"));
    }
}
