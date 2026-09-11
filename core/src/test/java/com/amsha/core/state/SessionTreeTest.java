package com.amsha.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class SessionTreeTest {

    @Test
    void testRootOf() {
        assertEquals("root-session", SessionTree.rootOf("root-session"));
        assertEquals("root-session", SessionTree.rootOf("root-session:delegate:researcher:123"));
        assertNull(SessionTree.rootOf(null));
    }

    @Test
    void testParentOf() {
        assertEquals(Optional.empty(), SessionTree.parentOf("root-session"));
        assertEquals(Optional.of("root-session"), SessionTree.parentOf("root-session:delegate:researcher:123"));
        assertEquals(Optional.empty(), SessionTree.parentOf(null));
    }

    @Test
    void testContainsAndIsDelegatedChild() {
        assertTrue(SessionTree.contains("root-session", "root-session"));
        assertTrue(SessionTree.contains("root-session", "root-session:delegate:researcher:123"));
        assertFalse(SessionTree.contains("root-session", "other-session"));
        assertFalse(SessionTree.contains(null, "root-session"));

        assertTrue(SessionTree.isDelegatedChild("root-session", "root-session:delegate:researcher:123"));
        assertFalse(SessionTree.isDelegatedChild("root-session", "root-session"));
        assertFalse(SessionTree.isDelegatedChild(null, "root-session"));
    }
}
