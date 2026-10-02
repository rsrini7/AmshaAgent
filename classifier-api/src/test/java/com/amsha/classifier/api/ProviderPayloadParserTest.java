package com.amsha.classifier.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link ProviderPayloadParser}.
 *
 * <p>The parser is the only thing standing between a provider's raw JSON and the decoder, and its
 * documented contract is that every helper returns {@code null} on malformed, partial, or wrong-typed
 * input so callers fail closed. Nothing may be coerced, defaulted, or invented.
 *
 * <p>The index-keyed collection shape is the subtle half: System One returns a Score's {@code legend}
 * and {@code probabilities} as a JSON object keyed by level index rendered as a string
 * ({@code {"0": 0.0, "1": 1.0}}) rather than as an array. The parser must reject a gap, a
 * non-canonical key, or an out-of-range index rather than silently compacting, because compaction
 * would <em>re-label levels</em> — turning a "level 2" answer into a "level 1" answer.
 *
 * <p>These tests exist because the decoder's own suite exercises the happy paths only; the rejection
 * branches are the ones that carry the fail-closed guarantee.
 */
class ProviderPayloadParserTest {

    // ---------- parseString ----------

    @Test
    void parseStringTrimsAndRejectsBlank() {
        assertEquals("hello", ProviderPayloadParser.parseString("  hello  "));
        assertNull(ProviderPayloadParser.parseString(""));
        assertNull(ProviderPayloadParser.parseString("   "));
        assertNull(ProviderPayloadParser.parseString(null));
        assertNull(ProviderPayloadParser.parseString(42));
    }

    // ---------- parseDouble ----------

    @Test
    void parseDoubleRejectsNumericStringsRatherThanCoercingThem() {
        assertEquals(1.5, ProviderPayloadParser.parseDouble(1.5));
        assertEquals(2.0, ProviderPayloadParser.parseDouble(2));
        // Deliberately rejected: a provider that sends a number as text is not the documented shape,
        // and coercing it here would hide a contract violation.
        assertNull(ProviderPayloadParser.parseDouble("1.5"));
        assertNull(ProviderPayloadParser.parseDouble(null));
    }

    @Test
    void parseDoubleRejectsNonFiniteValues() {
        assertNull(ProviderPayloadParser.parseDouble(Double.NaN));
        assertNull(ProviderPayloadParser.parseDouble(Double.POSITIVE_INFINITY));
        assertNull(ProviderPayloadParser.parseDouble(Double.NEGATIVE_INFINITY));
    }

    // ---------- parseLong ----------

    @Test
    void parseLongAcceptsNumbersAndNumericStrings() {
        assertEquals(5L, ProviderPayloadParser.parseLong(5));
        assertEquals(5L, ProviderPayloadParser.parseLong("  5  "));
        assertEquals(7L, ProviderPayloadParser.parseLong(7L));
    }

    @Test
    void parseLongRejectsNonNumericInput() {
        assertNull(ProviderPayloadParser.parseLong("abc"));
        assertNull(ProviderPayloadParser.parseLong(""));
        assertNull(ProviderPayloadParser.parseLong(null));
    }

    @Test
    void parseLongRejectsAFractionalNumber() {
        // Rejected, not truncated. A fractional token count is malformed input from an external
        // provider, and silently reading 5.7 as 5 would corrupt metadata while looking like a success.
        assertNull(ProviderPayloadParser.parseLong(5.7));
    }

    @Test
    void parseLongAcceptsAnExactlyIntegralFloatingPointValue() {
        // 5.0 is exactly 5, so it is well-formed — only a non-zero fractional part is malformed.
        assertEquals(5L, ProviderPayloadParser.parseLong(5.0));
        assertEquals(5L, ProviderPayloadParser.parseLong(5.0f));
    }

    @Test
    void parseLongRejectsNonFiniteNumbers() {
        assertNull(ProviderPayloadParser.parseLong(Double.NaN));
        assertNull(ProviderPayloadParser.parseLong(Double.POSITIVE_INFINITY));
        assertNull(ProviderPayloadParser.parseLong(Double.NEGATIVE_INFINITY));
    }

    @Test
    void parseLongRejectsAValueOutsideTheLongRange() {
        // longValue() would saturate this to Long.MAX_VALUE, inventing a plausible-looking count.
        assertNull(ProviderPayloadParser.parseLong(1e19));
    }

    @Test
    void parseLongRejectsBigIntegerJustBeyondLongMax() {
        assertNull(ProviderPayloadParser.parseLong(new BigInteger("9223372036854775808")));
    }

    @Test
    void parseLongKeepsIntegralLongsExact() {
        // Beyond 2^53 a double cannot represent every long, but an integral Long must still round-trip.
        assertEquals(Long.MAX_VALUE, ProviderPayloadParser.parseLong(Long.MAX_VALUE));
        assertEquals(9007199254740993L, ProviderPayloadParser.parseLong(9007199254740993L));
    }

    // ---------- isUnitInterval ----------

    @Test
    void isUnitIntervalIsInclusiveAtBothEnds() {
        assertTrue(ProviderPayloadParser.isUnitInterval(0.0));
        assertTrue(ProviderPayloadParser.isUnitInterval(1.0));
        assertTrue(ProviderPayloadParser.isUnitInterval(0.5));
        assertFalse(ProviderPayloadParser.isUnitInterval(-0.01));
        assertFalse(ProviderPayloadParser.isUnitInterval(1.01));
        assertFalse(ProviderPayloadParser.isUnitInterval(null));
    }

    // ---------- parseProbabilities ----------

    @Test
    void parseProbabilitiesAcceptsAnArray() {
        assertEquals(List.of(0.2, 0.8), ProviderPayloadParser.parseProbabilities(List.of(0.2, 0.8)));
    }

    @Test
    void parseProbabilitiesAcceptsAnIndexKeyedObject() {
        Map<String, Object> keyed = new HashMap<>();
        keyed.put("0", 0.25);
        keyed.put("1", 0.75);
        assertEquals(List.of(0.25, 0.75), ProviderPayloadParser.parseProbabilities(keyed));
    }

    @Test
    void parseProbabilitiesRejectsAValueOutsideTheUnitInterval() {
        assertNull(ProviderPayloadParser.parseProbabilities(List.of(0.5, 1.5)));
        assertNull(ProviderPayloadParser.parseProbabilities(List.of(-0.5)));
    }

    @Test
    void parseProbabilitiesRejectsAKeyedGapRatherThanCompacting() {
        Map<String, Object> gapped = new HashMap<>();
        gapped.put("0", 0.5);
        gapped.put("2", 0.5);
        // Compacting this to [0.5, 0.5] would re-label level 2 as level 1.
        assertNull(ProviderPayloadParser.parseProbabilities(gapped));
    }

    @Test
    void parseProbabilitiesRejectsANonCanonicalIndexKey() {
        for (String key : List.of("01", " 0", "-1", "1.0", "abc", "0x1")) {
            Map<String, Object> bad = new HashMap<>();
            bad.put(key, 0.5);
            assertNull(ProviderPayloadParser.parseProbabilities(bad), "key must be rejected: " + key);
        }
    }

    @Test
    void parseProbabilitiesRejectsANonStringKey() {
        // A JSON object always yields String keys, so this path only guards hand-built or
        // non-JSON input. It is still a documented rejection and must not throw.
        Map<Object, Object> nonStringKey = new HashMap<>();
        nonStringKey.put(0, 0.5);
        assertNull(ProviderPayloadParser.parseProbabilities(nonStringKey));
    }

    @Test
    void parseProbabilitiesRejectsAnIndexBeyondTheCollectionSize() {
        Map<String, Object> outOfRange = new HashMap<>();
        outOfRange.put("1", 0.5);
        assertNull(ProviderPayloadParser.parseProbabilities(outOfRange));
    }

    @Test
    void parseProbabilitiesRejectsAnIndexKeyTooLargeForAnInt() {
        // Matches the canonical-key pattern but overflows Integer.parseInt, so the catch must fire
        // rather than propagating a NumberFormatException out of a fail-closed parser.
        Map<String, Object> huge = new HashMap<>();
        huge.put("999999999999", 0.5);
        assertNull(ProviderPayloadParser.parseProbabilities(huge));
    }

    @Test
    void parseProbabilitiesRejectsANonNumericEntry() {
        assertNull(ProviderPayloadParser.parseProbabilities(Map.of("0", "0.5")));
        assertNull(ProviderPayloadParser.parseProbabilities(Map.of("0", true)));
    }

    @Test
    void parseProbabilitiesRejectsANullEntry() {
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("0", null);
        assertNull(ProviderPayloadParser.parseProbabilities(withNull));
    }

    @Test
    void parseProbabilitiesRejectsAScalarOrAbsentCollection() {
        assertNull(ProviderPayloadParser.parseProbabilities(null));
        assertNull(ProviderPayloadParser.parseProbabilities("0.5"));
        assertNull(ProviderPayloadParser.parseProbabilities(7));
    }

    @Test
    void parseProbabilitiesTreatsAnEmptyCollectionAsEmptyRatherThanMalformed() {
        assertEquals(List.of(), ProviderPayloadParser.parseProbabilities(List.of()));
        assertEquals(List.of(), ProviderPayloadParser.parseProbabilities(Map.of()));
    }

    // ---------- parseLabels ----------

    @Test
    void parseLabelsAcceptsBothShapes() {
        assertEquals(List.of("low", "high"), ProviderPayloadParser.parseLabels(List.of("low", "high")));

        Map<String, Object> keyed = new HashMap<>();
        keyed.put("0", "low");
        keyed.put("1", "high");
        assertEquals(List.of("low", "high"), ProviderPayloadParser.parseLabels(keyed));
    }

    @Test
    void parseLabelsRejectsABlankOrNonStringLabel() {
        assertNull(ProviderPayloadParser.parseLabels(List.of("low", "   ")));
        assertNull(ProviderPayloadParser.parseLabels(List.of("low", 7)));
    }

    @Test
    void parseLabelsRejectsAGapAndANonCanonicalKey() {
        Map<String, Object> gapped = new HashMap<>();
        gapped.put("0", "low");
        gapped.put("2", "high");
        assertNull(ProviderPayloadParser.parseLabels(gapped));

        Map<String, Object> nonCanonical = new HashMap<>();
        nonCanonical.put("00", "low");
        assertNull(ProviderPayloadParser.parseLabels(nonCanonical));
    }

    @Test
    void parseLabelsRejectsAScalarOrAbsentCollection() {
        assertNull(ProviderPayloadParser.parseLabels(null));
        assertNull(ProviderPayloadParser.parseLabels("low"));
    }

    // ---------- resolveTokenValue / isTokenReported ----------

    /** The accepted {@code usage} names for an input count, in precedence order. */
    private static final List<String> INPUT_KEYS = List.of("input_tokens", "prompt_tokens");

    @Test
    void resolveTokenValuePrefersTheFlatKeyOverTheNestedUsageObject() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("inputTokens", 11);
        raw.put("usage", Map.of("input_tokens", 22));
        assertEquals(11, ProviderPayloadParser.resolveTokenValue(raw, "inputTokens", INPUT_KEYS));
    }

    @Test
    void resolveTokenValueFallsBackToTheNestedUsageObject() {
        Map<String, Object> raw = Map.of("usage", Map.of("input_tokens", 22));
        assertEquals(22, ProviderPayloadParser.resolveTokenValue(raw, "inputTokens", INPUT_KEYS));
    }

    @Test
    void resolveTokenValueReadsTheOpenAiShapedUsageNames() {
        // A local runtime (LM Studio, Ollama, the `omlx` slot) reports usage in the OpenAI shape.
        // Before the alias list existed this resolved to null and isTokenReported said false, so the
        // decoder recorded no token data at all — a silent loss rather than a visible failure.
        Map<String, Object> raw = Map.of("usage", Map.of("prompt_tokens", 7, "completion_tokens", 3));
        assertEquals(7, ProviderPayloadParser.resolveTokenValue(raw, "inputTokens", INPUT_KEYS));
        assertEquals(
                3,
                ProviderPayloadParser.resolveTokenValue(
                        raw, "outputTokens", List.of("output_tokens", "completion_tokens")));
        assertTrue(ProviderPayloadParser.isTokenReported(raw, "inputTokens", INPUT_KEYS));
    }

    @Test
    void resolveTokenValuePrefersTheSystemOneNameWhenBothUsageNamesArePresent() {
        // A contradictory payload resolves deterministically to the contract's own spelling, rather
        // than to whichever entry the map happened to iterate first.
        Map<String, Object> raw = Map.of("usage", Map.of("input_tokens", 1, "prompt_tokens", 2));
        assertEquals(1, ProviderPayloadParser.resolveTokenValue(raw, "inputTokens", INPUT_KEYS));
    }

    @Test
    void tokenHelpersDoNotAcceptTheUsageNamesAtTheTopLevel() {
        // The alias list widens the `usage` object only. The flat keys belong to this contract, so an
        // OpenAI-shaped name at the top level stays unknown and the decoder fails closed on it.
        Map<String, Object> raw = Map.of("prompt_tokens", 5);
        assertFalse(ProviderPayloadParser.isTokenReported(raw, "inputTokens", INPUT_KEYS));
        assertNull(ProviderPayloadParser.resolveTokenValue(raw, "inputTokens", INPUT_KEYS));
    }

    @Test
    void resolveTokenValueReturnsNullWhenAbsentOrTheUsageObjectIsNotAMap() {
        assertNull(ProviderPayloadParser.resolveTokenValue(Map.of(), "inputTokens", INPUT_KEYS));
        assertNull(ProviderPayloadParser.resolveTokenValue(Map.of("usage", Map.of()), "inputTokens", INPUT_KEYS));
        assertNull(ProviderPayloadParser.resolveTokenValue(Map.of("usage", "not-a-map"), "inputTokens", INPUT_KEYS));
    }

    @Test
    void isTokenReportedDistinguishesAbsentFromPresentButUnparseable() {
        assertTrue(ProviderPayloadParser.isTokenReported(Map.of("inputTokens", 5), "inputTokens", INPUT_KEYS));
        assertTrue(ProviderPayloadParser.isTokenReported(
                Map.of("usage", Map.of("input_tokens", 5)), "inputTokens", INPUT_KEYS));
        assertFalse(ProviderPayloadParser.isTokenReported(Map.of(), "inputTokens", INPUT_KEYS));
        assertFalse(ProviderPayloadParser.isTokenReported(Map.of("usage", "not-a-map"), "inputTokens", INPUT_KEYS));

        // The documented reason both helpers exist: a key that is *present* but holds an unparseable
        // value must be distinguishable from a key that is *absent*. The parser reports the value
        // unparsed for exactly this reason, so the caller can fail closed instead of reporting zero.
        Map<String, Object> presentButNull = new HashMap<>();
        presentButNull.put("inputTokens", null);
        assertTrue(ProviderPayloadParser.isTokenReported(presentButNull, "inputTokens", INPUT_KEYS));
        assertNull(ProviderPayloadParser.resolveTokenValue(presentButNull, "inputTokens", INPUT_KEYS));
    }

    @Test
    void tokenHelpersTreatAMapValuedUsageObjectWithoutTheKeyAsAbsent() {
        // The `usage` object is present and is a map, but does not carry the requested key. This is
        // distinct from `usage` being absent or not a map at all, and must not be reported.
        Map<String, Object> wrongKey = Map.of("usage", Map.of("other_tokens", 5));
        assertFalse(ProviderPayloadParser.isTokenReported(wrongKey, "inputTokens", INPUT_KEYS));
        assertNull(ProviderPayloadParser.resolveTokenValue(wrongKey, "inputTokens", INPUT_KEYS));
    }
}
