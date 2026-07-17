package com.amsha.core.routing.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class ProviderOnboardingAdapterTest {

    private final StaticProviderCatalogAdapter adapter = new StaticProviderCatalogAdapter();

    @Test
    void descriptorsLoadedFromCatalog() {
        List<LlmProviderDescriptor> descriptors = adapter.descriptors();
        assertFalse(descriptors.isEmpty(), "catalog must load at least one provider");
        assertTrue(descriptors.stream().anyMatch(d -> d.id().equals("openai")));
        assertTrue(descriptors.stream().anyMatch(d -> d.id().equals("groq")));
    }

    @Test
    void descriptor_caseInsensitiveLookup() {
        Optional<LlmProviderDescriptor> openai = adapter.descriptor("OpenAI");
        assertTrue(openai.isPresent());
        assertEquals("openai", openai.get().id());
        // API-key provider advertises its default model in the catalog.
        assertTrue(openai.get().supportsModel(openai.get().modelCatalog().get(0).id()));
    }

    @Test
    void resolveModelWithinCatalog() {
        LlmProviderDescriptor openai = adapter.descriptor("openai").orElseThrow();
        String model = openai.modelCatalog().get(0).id();
        Optional<ModelEntry> entry = adapter.resolveModel("openai", model);
        assertTrue(entry.isPresent());
        assertEquals(model, entry.get().id());
        // Unknown model is not resolvable.
        assertTrue(adapter.resolveModel("openai", "does-not-exist").isEmpty());
    }

    @Test
    void validate_rejectsUnknownProvider() {
        ProviderValidationResult r =
                adapter.validate(new ConnectionValidationRequest("nope", "gpt-4o", "", false, List.of()));
        assertFalse(r.eligible());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("Unknown provider")));
    }

    @Test
    void validate_requiresApiKeyWhenProviderNeedsIt() {
        ProviderValidationResult r =
                adapter.validate(new ConnectionValidationRequest("openai", "gpt-4o", "", false, List.of()));
        assertFalse(r.eligible());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("API key")));
    }

    @Test
    void validate_acceptsApiKeyPresent() {
        String model =
                adapter.descriptor("openai").orElseThrow().modelCatalog().get(0).id();
        ProviderValidationResult r =
                adapter.validate(new ConnectionValidationRequest("openai", model, "", true, List.of()));
        assertTrue(r.eligible());
        assertTrue(r.errors().isEmpty());
    }

    @Test
    void validate_rejectsUnsupportedModel() {
        ProviderValidationResult r =
                adapter.validate(new ConnectionValidationRequest("openai", "unsupported-model-x", "", true, List.of()));
        assertFalse(r.eligible());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("does not advertise")));
    }

    @Test
    void validate_enforcesEgressAllowList() {
        String model =
                adapter.descriptor("openai").orElseThrow().modelCatalog().get(0).id();
        // Custom base URL host not in the allow-list must be rejected.
        ProviderValidationResult denied = adapter.validate(new ConnectionValidationRequest(
                "openai", model, "https://evil.example.com/v1", true, List.of("api.openai.com")));
        assertFalse(denied.eligible());
        assertTrue(denied.errors().stream().anyMatch(e -> e.contains("egress policy")));

        // Host within the allow-list is accepted.
        ProviderValidationResult allowed = adapter.validate(new ConnectionValidationRequest(
                "openai", model, "https://api.openai.com/v1", true, List.of("api.openai.com")));
        assertTrue(allowed.eligible());
    }

    @Test
    void descriptorRecordValidation() {
        // API-key provider with empty catalog must be rejected by the record contract.
        assertThrows(
                IllegalArgumentException.class,
                () -> new LlmProviderDescriptor("x", "X", "x", "API_KEY", null, true, false, List.of(), Map.of()));
        // No API key + empty catalog is allowed (local/ollama-style with discovered models).
        LlmProviderDescriptor local = new LlmProviderDescriptor(
                "local", "Local", "local", "NONE", "http://localhost", false, true, List.of(), Map.of());
        assertEquals("NONE", local.authScheme());
        assertSame(local.attributes(), Map.copyOf(local.attributes()));
    }
}
