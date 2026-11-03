package com.amsha.core.tool.attempt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class Rfc8785CanonicalizationTest {

    @Test
    @DisplayName("RFC 8785 §3.2.3: Official sample values canonicalization")
    void testOfficialRfc8785ValuesSample() {
        String input = """
                {
                  "numbers": [333333333.33333329, 1E30, 4.50, 2e-3, 0.000000000000000000000000001],
                  "string": "\\u20ac$\\u000F\\u000aA'\\u0042\\u0022\\u005c\\\\\\\"\\/",
                  "literals": [null, true, false]
                }
                """;

        String expected =
                "{\"literals\":[null,true,false],\"numbers\":[333333333.3333333,1e+30,4.5,0.002,1e-27],\"string\":\"€$\\u000f\\nA'B\\\"\\\\\\\\\\\"/\"}";

        String canonical = CanonicalJsonUtil.canonicalize(input);
        assertEquals(expected, canonical);

        byte[] canonicalBytes = CanonicalJsonUtil.canonicalizeToUtf8(input);
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), canonicalBytes);
    }

    @Test
    @DisplayName("RFC 8785: Array preservation and nested property sorting (arrays.json)")
    void testArraysVector() {
        String input = "[ 56, { \"d\": true, \"10\": null, \"1\": [ ] } ]";
        String expected = "[56,{\"1\":[],\"10\":null,\"d\":true}]";

        assertEquals(expected, CanonicalJsonUtil.canonicalize(input));
    }

    @Test
    @DisplayName("RFC 8785: UTF-16 code unit ordering vs locale sorting (french.json)")
    void testFrenchSortingVector() {
        String input = """
                {
                  "peach": "This sorting order",
                  "péché": "is wrong according to French",
                  "pêche": "but canonicalization MUST",
                  "sin": "ignore locale"
                }
                """;

        String expected =
                "{\"peach\":\"This sorting order\",\"péché\":\"is wrong according to French\",\"pêche\":\"but canonicalization MUST\",\"sin\":\"ignore locale\"}";

        assertEquals(expected, CanonicalJsonUtil.canonicalize(input));
    }

    @Test
    @DisplayName("RFC 8785: Nested structures, empty keys, and control chars (structures.json)")
    void testStructuresVector() {
        String input = """
                { "1": {"f": {"f": "hi","F": 5} ,"\\n": 56.0}, "10": { }, "": "empty", "a": { }, "111": [ {"e": "yes","E": "no" } ], "A": { } }
                """;
        String expected =
                "{\"\":\"empty\",\"1\":{\"\\n\":56,\"f\":{\"F\":5,\"f\":\"hi\"}},\"10\":{},\"111\":[{\"E\":\"no\",\"e\":\"yes\"}],\"A\":{},\"a\":{}}";

        assertEquals(expected, CanonicalJsonUtil.canonicalize(input));
    }

    @Test
    @DisplayName("RFC 8785: Negative zero normalizes to 0")
    void testNegativeZero() {
        String input1 = "{\"val\": -0}";
        String input2 = "{\"val\": -0.0}";
        String expected = "{\"val\":0}";

        assertEquals(expected, CanonicalJsonUtil.canonicalize(input1));
        assertEquals(expected, CanonicalJsonUtil.canonicalize(input2));
    }

    @Test
    @DisplayName("RFC 8785: Number formatting thresholds and integer equivalence")
    void testNumberFormatting() {
        assertEquals("{\"a\":10,\"b\":10}", CanonicalJsonUtil.canonicalize("{\"b\": 10.0, \"a\": 10}"));
        assertEquals("{\"a\":100,\"b\":100}", CanonicalJsonUtil.canonicalize("{\"b\": 1e2, \"a\": 100}"));
        assertEquals("{\"val\":1e+21}", CanonicalJsonUtil.canonicalize("{\"val\": 1000000000000000000000}"));
    }

    @Test
    @DisplayName("RFC 8785: Whitespace removal across complex document")
    void testWhitespaceRemoval() {
        String input = """
                {
                   \t"b" :   [ 1,  2,   3 ],
                   \r\n"a" :   "hello world"
                }
                """;
        assertEquals("{\"a\":\"hello world\",\"b\":[1,2,3]}", CanonicalJsonUtil.canonicalize(input));
    }

    @Test
    @DisplayName("RFC 8785: Invalid JSON fails closed with IllegalArgumentException")
    void testInvalidJsonFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> CanonicalJsonUtil.canonicalize(null));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJsonUtil.canonicalize(""));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJsonUtil.canonicalize("{not valid json}"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJsonUtil.canonicalizeToUtf8(null));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJsonUtil.canonicalizeToUtf8("{unclosed"));
    }
}
