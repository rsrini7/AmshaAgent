package com.amsha.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.amsha.onboarding.dto.OnboardingStepDescriptorDto;
import com.amsha.onboarding.extension.McpOnboardingExtension;
import com.amsha.onboarding.i18n.OnboardingI18nService;
import com.amsha.onboarding.spi.OnboardingExtension;
import com.amsha.onboarding.validation.OnboardingStepValidator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OnboardingStepDescriptorServiceTest {

    private OnboardingStepDescriptorService service;
    private OnboardingI18nService i18nService;
    private Instance<OnboardingExtension> extensions;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        i18nService = new OnboardingI18nService();
        OnboardingStepValidator validator = new OnboardingStepValidator(i18nService);
        extensions = mock(Instance.class);
        McpOnboardingExtension mcpExt = new McpOnboardingExtension();
        when(extensions.iterator())
                .thenAnswer(inv -> List.<OnboardingExtension>of(mcpExt).iterator());

        service = new OnboardingStepDescriptorService();
        service.i18nService = i18nService;
        service.stepValidator = validator;
        service.extensions = extensions;
    }

    @Test
    void resolvesEffectiveDescriptorsForPlan() {
        List<String> plan = List.of("welcome", "connections", "mcp", "review");
        List<OnboardingStepDescriptorDto> descriptors = service.getEffectiveDescriptors(plan, Locale.ENGLISH);

        assertEquals(4, descriptors.size());
        assertEquals("welcome", descriptors.get(0).id());
        assertEquals("Welcome to Amsha Agent", descriptors.get(0).title());
        assertFalse(descriptors.get(0).optional());

        assertEquals("connections", descriptors.get(1).id());
        assertEquals("LLM Connections", descriptors.get(1).title());

        assertEquals("mcp", descriptors.get(2).id());
        assertEquals("Model Context Protocol (MCP)", descriptors.get(2).title());
        assertTrue(descriptors.get(2).optional());
        assertFalse(descriptors.get(2).fields().isEmpty());
    }

    @Test
    void resolvesExtensionFieldsWithLocalizedLabels() {
        Optional<OnboardingStepDescriptorDto> mcp = service.getDescriptor("mcp", 3, Locale.ENGLISH);
        assertTrue(mcp.isPresent());
        assertEquals("Model Context Protocol (MCP)", mcp.get().title());

        assertEquals(3, mcp.get().fields().size());
        assertEquals("name", mcp.get().fields().get(0).name());
        assertEquals("Server Name", mcp.get().fields().get(0).title());
        assertEquals("TEXT", mcp.get().fields().get(0).type());
        assertTrue(mcp.get().fields().get(0).required());

        assertEquals("endpoint", mcp.get().fields().get(1).name());
        assertEquals("Server Endpoint URL", mcp.get().fields().get(1).title());
        assertEquals("URL", mcp.get().fields().get(1).type());

        assertEquals("category", mcp.get().fields().get(2).name());
        assertEquals("Tool Category", mcp.get().fields().get(2).title());
        assertEquals("SELECT", mcp.get().fields().get(2).type());
        assertFalse(mcp.get().fields().get(2).options().isEmpty());
    }

    @Test
    void fallsBackToEnglishWhenRequestedLocaleMissing() {
        Optional<OnboardingStepDescriptorDto> mcp = service.getDescriptor("mcp", 3, Locale.GERMAN);
        assertTrue(mcp.isPresent());
        assertEquals("Model Context Protocol (MCP)", mcp.get().title());
    }
}
