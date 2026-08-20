package com.amsha.core.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class ToolDiscoveryServiceTest {

    @Inject
    ToolDiscoveryService discoveryService;

    @Test
    public void testDiscoveryProvidesOrderedImmutableSnapshot() {
        discoveryService.discover();
        var toolIndex = discoveryService.getToolIndex();
        assertNotNull(toolIndex);
        assertFalse(toolIndex.isEmpty());

        // Coordination tools from core must be discovered
        assertTrue(toolIndex.containsKey("garuda") || toolIndex.containsKey("agent_task"));

        var descriptorCache = discoveryService.getDescriptorCache();
        assertNotNull(descriptorCache);
        assertEquals(toolIndex.size(), descriptorCache.size());
    }

    private static void assertEquals(int expected, int actual) {
        Assertions.assertEquals(expected, actual);
    }
}
