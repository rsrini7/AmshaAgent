package com.arcclaw.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MessageTagTest {

    @Test
    void testTagAddsPrefix() {
        String tagged = MessageTag.tag("some content");
        assertTrue(tagged.startsWith(MessageTag.COMPLIANCE_PREFIX));
        assertTrue(tagged.contains("some content"));
    }

    @Test
    void testIsTaggedReturnsTrueForTaggedContent() {
        assertTrue(MessageTag.isTagged(MessageTag.tag("approval message")));
    }

    @Test
    void testIsTaggedReturnsFalseForUntaggedContent() {
        assertFalse(MessageTag.isTagged("Human Approved: something"));
        assertFalse(MessageTag.isTagged("as per our agreement"));
        assertFalse(MessageTag.isTagged("Tool binding contract"));
    }

    @Test
    void testIsTaggedNullSafe() {
        assertFalse(MessageTag.isTagged(null));
        assertFalse(MessageTag.isTagged(""));
        assertFalse(MessageTag.isTagged("   "));
    }

    @Test
    void testPrefixIsStable() {
        assertEquals("[ARCCLAW:2f4a9c]", MessageTag.COMPLIANCE_PREFIX);
    }
}
