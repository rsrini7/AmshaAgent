package com.arcclaw.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DelegationRoutingProviderTest {

    private final DelegationRoutingProvider provider = new DelegationRoutingProvider();

    @Test
    void determineRole_routesDelegationsToCoordinator() {
        assertTrue(provider.fastPathTriggers().get(0).matcher("Ask shiva to debug this").find());
        assertTrue(provider.fastPathTriggers().get(0).matcher("tell brahma to write some code").find());
        assertTrue(provider.fastPathTriggers().get(0).matcher("delegate to saraswati").find());
        assertTrue(provider.fastPathTriggers().get(0).matcher("have the synthesizer look at this").find());
        assertTrue(provider.fastPathTriggers().get(0).matcher("instruct brihaspati to make an ADR").find());
        
        assertEquals("coordinator", provider.fastPathRole().get());
    }

    @Test
    void determineRole_ignoresCoordinatorReferences() {
        assertFalse(provider.fastPathTriggers().get(0).matcher("Ask vishnu to do something").find());
        assertFalse(provider.fastPathTriggers().get(0).matcher("tell the coordinator").find());
    }

    @Test
    void determineRole_ignoresStandardQueries() {
        assertFalse(provider.fastPathTriggers().get(0).matcher("What is the weather?").find());
        assertFalse(provider.fastPathTriggers().get(0).matcher("Fix the NPE in Main.java").find());
    }

    @Test
    void testPriorityAndDirectives() {
        assertEquals(5, provider.priority());
        assertFalse(provider.promptDirectives().isEmpty());
    }
}
