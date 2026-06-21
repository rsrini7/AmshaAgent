package com.amsha.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.amsha.AmshaInfo;
import com.amsha.core.tools.ToolRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SystemEnvironmentContextEnricherTest {

    @Mock
    ToolRegistry toolRegistry;

    @InjectMocks
    SystemEnvironmentContextEnricher enricher;

    @Test
    void testEnrichmentContainsEnvironmentMetadata() {
        when(toolRegistry.registeredToolNames()).thenReturn("github, sandbox_exec");

        String result = enricher.enrich("session-1", "user-1", "fix bug", "Base system prompt.");

        assertTrue(result.contains("Base system prompt."), "Should contain base prompt");
        assertTrue(result.contains("## Host Environment Context"), "Should contain header");
        assertTrue(result.contains(System.getProperty("user.dir")), "Should contain working directory");
        assertTrue(result.contains("OS"), "Should contain OS");
        assertTrue(result.contains("Java"), "Should contain Java");
        assertTrue(result.contains(AmshaInfo.VERSION), "Should contain Amsha Agent version");
        assertTrue(result.contains("github, sandbox_exec"), "Should contain tool list");
    }

    @Test
    void testEnrichmentWithNoTools() {
        when(toolRegistry.registeredToolNames()).thenReturn("");

        String result = enricher.enrich("session-2", "user-2", "hello", "Base prompt.");

        assertTrue(result.contains("Registered Agent Tools**: none"));
    }
}
