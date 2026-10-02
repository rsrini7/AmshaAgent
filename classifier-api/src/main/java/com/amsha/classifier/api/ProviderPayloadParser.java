package com.amsha.classifier.api;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Strict, provider-neutral scalar and index-keyed collection parsing for decision payloads.
 *
 * <p>Every helper returns {@code null} on malformed, partial, or wrong-typed input so callers can fail
 * closed. Nothing is coerced, defaulted, or invented: an absent value stays absent.
 *
 * <p>Index-keyed collections exist because System One providers return a Score's {@code legend} and
 * {@code probabilities} as JSON objects keyed by level index rendered as a string
 * (for example {@code {"0": 0.0, "1": 1.0}}) rather than as JSON arrays. Keys must cover a contiguous
 * {@code 0..n-1} range exactly once; a gap, a non-canonical key, or an out-of-range key is malformed
 * and is rejected rather than silently compacted, because compaction would re-label levels.
 *
 * <p>Because the accepted keys are canonical, two distinct keys can never resolve to the same slot, so
 * a duplicate-index collision is unrepresentable in a {@link Map} and the collision test in the
 * index-ordering helper cannot fire for any input this parser can receive. That test is retained as a
 * guard should the key pattern ever be relaxed to accept aliases; it is unreachable today.
 */
public final class ProviderPayloadParser {

    /** Canonical non-negative integer: no sign, whitespace, or leading zeros. */
    private static final Pattern INDEX_KEY = Pattern.compile("0|[1-9][0-9]*");

    private ProviderPayloadParser() {}

    /**
     * Parses a non-blank trimmed string.
     *
     * @param obj raw value
     * @return trimmed string, or null if absent, blank, or not a string
     */
    public static String parseString(Object obj) {
        if (obj instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        return null;
    }

    /**
     * Parses a finite numeric value. Numeric strings are deliberately rejected.
     *
     * @param obj raw value
     * @return finite double, or null
     */
    public static Double parseDouble(Object obj) {
        if (obj instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        return null;
    }

    /**
     * Parses an integral count, failing closed on anything not exactly representable as a {@code long}.
     *
     * <p>This gate reads metadata out of external provider payloads, so a malformed number is rejected
     * rather than coerced. {@link Number#longValue()} would silently truncate {@code 5.7} to {@code 5},
     * map {@code NaN} to {@code 0}, and saturate an out-of-range value to {@link Long#MAX_VALUE} — each
     * corrupting a token count while looking like a success. {@link #parseDouble} guards non-finite
     * values for the same reason; this method additionally requires the value to be integral, so the two
     * now agree and a caller sees one consistent "malformed metadata" outcome.
     *
     * @param obj raw value
     * @return the exact long value, or null when the value is absent, non-numeric, non-integral, or
     *     outside the {@code long} range
     */
    public static Long parseLong(Object obj) {
        if (obj instanceof Number n) {
            try {
                // Avoid double rounding, which can make an out-of-range integer appear in range.
                return new BigDecimal(n.toString()).longValueExact();
            } catch (NumberFormatException | ArithmeticException _) {
                return null;
            }
        }
        if (obj instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException _) {
                return null;
            }
        }
        return null;
    }

    /**
     * Whether a parsed value lies in the closed unit interval.
     *
     * @param value parsed value
     * @return true if non-null and within [0.0, 1.0]
     */
    public static boolean isUnitInterval(Double value) {
        return value != null && value >= 0.0 && value <= 1.0;
    }

    /**
     * Parses a probability collection in either supported shape: a JSON array of numbers, or a JSON
     * object keyed by level index as a string.
     *
     * @param obj raw collection
     * @return ordered probabilities, or null if malformed or any entry is outside [0.0, 1.0]
     */
    public static List<Double> parseProbabilities(Object obj) {
        List<Object> ordered = toIndexOrderedList(obj);
        if (ordered == null) {
            return null;
        }
        List<Double> parsed = new ArrayList<>(ordered.size());
        for (Object item : ordered) {
            Double p = parseDouble(item);
            if (!isUnitInterval(p)) {
                return null;
            }
            parsed.add(p);
        }
        return parsed;
    }

    /**
     * Parses a label collection in either supported shape: a JSON array of strings, or a JSON object
     * keyed by level index as a string.
     *
     * @param obj raw collection
     * @return ordered non-blank labels, or null if malformed
     */
    public static List<String> parseLabels(Object obj) {
        List<Object> ordered = toIndexOrderedList(obj);
        if (ordered == null) {
            return null;
        }
        List<String> parsed = new ArrayList<>(ordered.size());
        for (Object item : ordered) {
            String label = parseString(item);
            if (label == null) {
                return null;
            }
            parsed.add(label);
        }
        return parsed;
    }

    /**
     * Resolves a token-count value from a flat top-level key or from a nested System One {@code usage}
     * object. The raw value is returned unparsed so the caller can distinguish absent from malformed.
     *
     * <p>The top-level key is exact: the flat keys belong to this contract. The nested {@code usage}
     * object belongs to the provider, so it is looked up by an ordered list of accepted names and the
     * first one present wins. That is what lets a local runtime reporting OpenAI-shaped usage
     * ({@code prompt_tokens} / {@code completion_tokens}) be read without also accepting those names
     * at the top level, where they are not part of any documented envelope.
     *
     * @param raw response payload
     * @param flatKey top-level key, matched exactly
     * @param usageKeys accepted names within {@code usage}, in precedence order; empty means the nested
     *     object is not consulted
     * @return raw value, or null if absent from both locations
     */
    public static Object resolveTokenValue(Map<String, Object> raw, String flatKey, List<String> usageKeys) {
        if (raw.containsKey(flatKey)) {
            return raw.get(flatKey);
        }
        if (raw.get("usage") instanceof Map<?, ?> usage) {
            for (String usageKey : usageKeys) {
                if (usage.containsKey(usageKey)) {
                    return usage.get(usageKey);
                }
            }
        }
        return null;
    }

    /**
     * Whether a token count was reported at either supported location.
     *
     * @param raw response payload
     * @param flatKey top-level key, matched exactly
     * @param usageKeys accepted names within {@code usage}, in precedence order
     * @return true if the key is present at either location
     */
    public static boolean isTokenReported(Map<String, Object> raw, String flatKey, List<String> usageKeys) {
        if (raw.containsKey(flatKey)) {
            return true;
        }
        if (!(raw.get("usage") instanceof Map<?, ?> usage)) {
            return false;
        }
        for (String usageKey : usageKeys) {
            if (usage.containsKey(usageKey)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Normalizes a JSON array or an index-keyed JSON object into a positional list. An index-keyed
     * object must cover a contiguous {@code 0..n-1} range exactly once.
     *
     * @param obj raw collection
     * @return positional list of raw values, or null if malformed
     */
    private static List<Object> toIndexOrderedList(Object obj) {
        if (obj instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        if (!(obj instanceof Map<?, ?> map)) {
            return null;
        }
        if (map.isEmpty()) {
            return List.of();
        }
        Object[] slots = new Object[map.size()];
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                return null;
            }
            int index = parseIndexKey(key);
            if (index < 0 || index >= slots.length || slots[index] != null) {
                return null;
            }
            slots[index] = entry.getValue();
        }
        for (Object slot : slots) {
            if (slot == null) {
                return null;
            }
        }
        return List.of(slots);
    }

    private static int parseIndexKey(String key) {
        if (!INDEX_KEY.matcher(key).matches()) {
            return -1;
        }
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException _) {
            return -1;
        }
    }
}
