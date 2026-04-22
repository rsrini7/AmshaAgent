package com.arcclaw.core.util;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class FuzzyMatcherTest {

    @Test
    void testLevenshteinDistance() {
        assertEquals(0, FuzzyMatcher.distance("one", "one"));
        assertEquals(1, FuzzyMatcher.distance("one", "oen"));
        assertEquals(1, FuzzyMatcher.distance("one", "noe"));
        assertEquals(0, FuzzyMatcher.distance("schedule", "schedule"));
        assertEquals(1, FuzzyMatcher.distance("schedule", "schdeule")); // transposition dist 1
        assertEquals(3, FuzzyMatcher.distance("abc", "def"));
    }

    @Test
    void testIsClose() {
        // Short words (max distance 1)
        assertTrue(FuzzyMatcher.isClose("oen", "one"));
        assertTrue(FuzzyMatcher.isClose("noe", "one"));
        assertFalse(FuzzyMatcher.isClose("noe", "abc"));

        // Long words (max distance 2)
        assertTrue(FuzzyMatcher.isClose("scheduel", "schedule"));
        assertTrue(FuzzyMatcher.isClose("schdeule", "schedule"));
        assertFalse(FuzzyMatcher.isClose("scheduel-me-now", "schedule"));
    }

    @Test
    void testContainsFuzzy() {
        assertTrue(FuzzyMatcher.containsFuzzy("scheduel to fetch rsrini7/ArcClaw", "schedule"));
        assertTrue(FuzzyMatcher.containsFuzzy("please oen min later", "one"));
        assertFalse(FuzzyMatcher.containsFuzzy("nothing close here", "schedule"));
    }
}
