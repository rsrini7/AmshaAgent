package com.amsha.onboarding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

import com.amsha.llm.api.LlmProviderDescriptor;
import com.amsha.llm.api.LlmProviderOnboardingAdapter;
import com.amsha.llm.api.ModelEntry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProviderCatalogResourceUnitTest {

    @Mock
    LlmProviderOnboardingAdapter providerAdapter;

    @InjectMocks
    ProviderCatalogResource resource;

    @Test
    void testGetCatalogEmpty() {
        when(providerAdapter.descriptors()).thenReturn(List.of());
        Response response = resource.getCatalog();
        assertEquals(404, response.getStatus());
    }

    @Test
    void testGetCatalogSuccess() {
        ModelEntry model = new ModelEntry("llama3-8b-8192", "Llama 3 8B");
        LlmProviderDescriptor desc = new LlmProviderDescriptor(
                "groq", "Groq", "Groq AI", "API_KEY", "https://api.groq.com", true, false, List.of(model), Map.of());
        when(providerAdapter.descriptors()).thenReturn(List.of(desc));

        Response response = resource.getCatalog();
        assertEquals(200, response.getStatus());
        assertNotNull(response.getEntity());
    }
}
