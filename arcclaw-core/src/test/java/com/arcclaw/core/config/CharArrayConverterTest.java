package com.arcclaw.core.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class CharArrayConverterTest {

    @Test
    void testConvertValidString() {
        CharArrayConverter converter = new CharArrayConverter();
        char[] result = converter.convert("testvalue");
        assertArrayEquals(new char[]{'t','e','s','t','v','a','l','u','e'}, result);
    }

    @Test
    void testConvertNullString() {
        CharArrayConverter converter = new CharArrayConverter();
        char[] result = converter.convert(null);
        assertArrayEquals(new char[0], result);
    }
}
