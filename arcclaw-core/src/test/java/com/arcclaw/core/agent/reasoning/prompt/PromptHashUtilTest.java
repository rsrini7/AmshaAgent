package com.arcclaw.core.agent.reasoning.prompt;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PromptHashUtilTest {

    @Test
    void sha256_isDeterministic() {
        assertEquals(PromptHashUtil.sha256("hello"), PromptHashUtil.sha256("hello"));
    }

    @Test
    void sha256_differsForDifferentInput() {
        assertNotEquals(PromptHashUtil.sha256("hello"), PromptHashUtil.sha256("world"));
    }

    @Test
    void sha256_hasExpectedLength() {
        assertEquals(64, PromptHashUtil.sha256("test content").length());
    }

    @Test
    void parseHash_returnsEmpty_whenNull() {
        assertEquals("", PromptHashUtil.parseHash(null));
    }

    @Test
    void parseHash_returnsEmpty_whenBlank() {
        assertEquals("", PromptHashUtil.parseHash(""));
    }

    @Test
    void parseHash_returnsEmpty_whenNoSha256() {
        assertEquals("", PromptHashUtil.parseHash("{\"source\":\"classpath\"}"));
    }

    @Test
    void parseHash_extractsSha256() {
        assertEquals("abc123def456", PromptHashUtil.parseHash("{\"source\":\"classpath\",\"sha256\":\"abc123def456\"}"));
    }
}
