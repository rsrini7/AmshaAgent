package com.amsha.onboarding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;
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

    @Mock
    SecurityContext securityContext;

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

    @Test
    void testGetProviderModelsSuccess() {
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn("owner-1");
        when(securityContext.getUserPrincipal()).thenReturn(principal);

        ModelEntry model = new ModelEntry("gpt-4o", "GPT-4o");
        when(providerAdapter.discoverModels(eq("openai"), isNull(), eq("test-key"), eq("owner-1")))
                .thenReturn(List.of(model));

        Response response = resource.getProviderModels("openai", null, "test-key");
        assertEquals(200, response.getStatus());
        assertEquals(List.of(model), response.getEntity());
    }

    @Test
    void testGetProviderModelsUnauthenticatedReturns401() {
        when(securityContext.getUserPrincipal()).thenReturn(null);

        Response response = resource.getProviderModels("openai", null, "test-key");
        assertEquals(401, response.getStatus());
    }

    @Test
    void testGetProviderModelsBlankProviderReturnsBadRequest() {
        Response response = resource.getProviderModels("  ", null, null);
        assertEquals(400, response.getStatus());
    }

    @Test
    void testGetProviderModelsNullProviderReturnsBadRequest() {
        Response response = resource.getProviderModels(null, null, null);
        assertEquals(400, response.getStatus());
    }
}
