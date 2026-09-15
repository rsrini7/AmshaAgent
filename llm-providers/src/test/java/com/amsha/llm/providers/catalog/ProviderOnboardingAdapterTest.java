package com.amsha.llm.providers.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.llm.api.ConnectionValidationRequest;
import com.amsha.llm.api.LlmProviderDescriptor;
import com.amsha.llm.api.ModelEntry;
import com.amsha.llm.api.ProviderValidationResult;

import org.junit.jupiter.api.Test;

class ProviderOnboardingAdapterTest {

    private final StaticProviderCatalogAdapter adapter = new StaticProviderCatalogAdapter();

    @Test
    void descriptorsLoadedFromCatalog() {
        List<LlmProviderDescriptor> descriptors = adapter.descriptors();
        assertFalse(descriptors.isEmpty(), "catalog must load at least one provider");
        assertTrue(descriptors.stream().anyMatch(d -> d.id().equals("openai")));
        assertTrue(descriptors.stream().anyMatch(d -> d.id().equals("groq")));
        assertTrue(descriptors.stream().noneMatch(d -> d.id().equals("anthropic")));
        LlmProviderDescriptor groq = adapter.descriptor("groq").orElseThrow();
        assertEquals("https://api.groq.com/openai/v1", groq.defaultBaseUrl());
        assertEquals(
                "CLI_OAUTH", adapter.descriptor("claude-code").orElseThrow().authScheme());
    }

    @Test
    void descriptorsLoadWithoutThreadContextClassLoader() {
        Thread thread = Thread.currentThread();
        ClassLoader originalClassLoader = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(null);
            assertFalse(
                    adapter.descriptors().isEmpty(),
                    "catalog loading must not depend on the thread context classloader");
        } finally {
            thread.setContextClassLoader(originalClassLoader);
        }
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

        ProviderValidationResult malformed = adapter.validate(
                new ConnectionValidationRequest("openai", model, "api.openai.com/v1", true, List.of()));
        assertFalse(malformed.eligible());
        assertTrue(malformed.errors().stream().anyMatch(e -> e.contains("absolute HTTP(S) URL")));
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

    @Test
    void validateRejectsACustomBaseUrlThatIsNotParseable() {
        String model =
                adapter.descriptor("openai").orElseThrow().modelCatalog().get(0).id();

        // An unclosed IPv6 literal makes URI parsing itself throw, rather than merely yielding no host.
        ProviderValidationResult r = adapter.validate(
                new ConnectionValidationRequest("openai", model, "http://[not-a-host", true, List.of()));

        assertFalse(r.eligible());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("absolute HTTP(S) URL")));
    }

    @Test
    void discoverModelsFallsBackToTheStaticCatalogWithoutALiveService() {
        List<ModelEntry> advertised = adapter.descriptor("openai").orElseThrow().modelCatalog();

        // No live service is wired in a plain unit test, so the advertised catalog is the answer.
        assertSame(advertised, adapter.discoverModels("openai", null, "sk-x", "user-1"));
    }

    @Test
    void discoverModelsForAnUnknownProviderIsEmpty() {
        assertEquals(List.of(), adapter.discoverModels("no-such-provider", null, null, null));
    }

    @Test
    void discoverModelsDelegatesToTheLiveServiceWhenPresent() {
        StaticProviderCatalogAdapter withLive = new StaticProviderCatalogAdapter();
        LiveProviderCatalogService live = mock(LiveProviderCatalogService.class);
        List<ModelEntry> fallback = withLive.descriptor("openai").orElseThrow().modelCatalog();
        List<ModelEntry> liveModels = List.of(new ModelEntry("gpt-5-live", "GPT-5 Live"));
        when(live.discoverModels("openai", "https://gw.example.com/v1", "sk-x", "user-1", fallback))
                .thenReturn(liveModels);
        withLive.liveCatalogService = live;

        assertSame(liveModels, withLive.discoverModels("openai", "https://gw.example.com/v1", "sk-x", "user-1"));
        verify(live).discoverModels("openai", "https://gw.example.com/v1", "sk-x", "user-1", fallback);
    }
}
