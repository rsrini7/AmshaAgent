package com.amsha.tools.builtin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import com.amsha.core.tools.ToolDiscoveryCompleted;
import com.amsha.core.tools.ToolDiscoveryService;
import com.amsha.core.tools.ToolRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ToolSearchIndexTest {

    @Mock
    ToolDiscoveryService discoveryService;

    ToolSearchIndex searchIndex;

    @BeforeEach
    void setUp() {
        searchIndex = new ToolSearchIndex();
        searchIndex.discoveryService = discoveryService;
    }

    @Test
    void testRebuildAndSearch() {
        assertFalse(searchIndex.isReady());
        assertEquals(0, searchIndex.size());

        ToolRegistry.ToolDescriptor desc1 =
                new ToolRegistry.ToolDescriptor("web_fetch", "Fetches web page content as markdown or text", "{}");
        ToolRegistry.ToolDescriptor desc2 = new ToolRegistry.ToolDescriptor(
                "brave_web_search", "Searches the internet using Brave search API", "{}");
        ToolRegistry.ToolDescriptor desc3 = new ToolRegistry.ToolDescriptor(
                "manage_checklist", "Manages step-by-step checklists and task items", "{}");

        when(discoveryService.getDescriptorCache())
                .thenReturn(Map.of(
                        "web_fetch", desc1,
                        "brave_web_search", desc2,
                        "manage_checklist", desc3));

        searchIndex.onDiscoveryCompleted(new ToolDiscoveryCompleted(3));

        assertTrue(searchIndex.isReady());
        assertEquals(3, searchIndex.size());

        List<String> results = searchIndex.search("web search internet");
        assertNotNull(results);
        assertFalse(results.isEmpty());
        assertTrue(results.contains("brave_web_search") || results.contains("web_fetch"));

        List<String> checklistResults = searchIndex.search("manage_checklist");
        assertFalse(checklistResults.isEmpty());
        assertTrue(checklistResults.contains("manage_checklist"));

        // Blank / invalid query returns empty list
        assertTrue(searchIndex.search(null).isEmpty());
        assertTrue(searchIndex.search("").isEmpty());
        assertTrue(searchIndex.search("   ").isEmpty());
        assertTrue(searchIndex.search("web", 0).isEmpty());
        assertTrue(searchIndex.search("web", -1).isEmpty());
    }
}
