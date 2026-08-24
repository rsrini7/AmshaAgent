package com.amsha.llm.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StructuredOutputModeTest {

    @Test
    void testEnumValues() {
        assertEquals(StructuredOutputMode.NATIVE, StructuredOutputMode.valueOf("NATIVE"));
        assertEquals(StructuredOutputMode.PROMPT_DIRECTIVE, StructuredOutputMode.valueOf("PROMPT_DIRECTIVE"));
        assertEquals(StructuredOutputMode.LENIENT, StructuredOutputMode.valueOf("LENIENT"));
    }
}
