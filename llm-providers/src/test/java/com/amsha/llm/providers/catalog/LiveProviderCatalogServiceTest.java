package com.amsha.llm.providers.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import com.amsha.common.config.ConfigKeys;
import com.amsha.core.audit.AuditService;
import com.amsha.llm.api.ModelEntry;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultKey;
import com.amsha.security.http.GovernedTransportService;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LiveProviderCatalogServiceTest {

    private LiveProviderCatalogService service;
    private GovernedTransportService mockTransport;
    private AuditService mockAudit;
    private HttpResponse<String> mockResponse;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        service = new LiveProviderCatalogService();
        mockTransport = mock(GovernedTransportService.class);
        mockAudit = mock(AuditService.class);
        mockResponse = mock(HttpResponse.class);

        service.governedTransport = mockTransport;
        service.auditService = mockAudit;
        service.objectMapper = new ObjectMapper();
        service.clearCache();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testDiscoverModelsOpenAiFormat() {
        String json = """
                {
                  "data": [
                    {"id": "gpt-4o", "display_name": "GPT-4o"},
                    {"id": "gpt-4o-mini", "display_name": "GPT-4o Mini"}
                  ]
                }
                """;
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(json);
        when(mockTransport.<String>send(any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(mockResponse);

        List<ModelEntry> fallback = List.of(new ModelEntry("fallback-model", "Fallback"));
        List<ModelEntry> result = service.discoverModels("openai", null, "sk-test", "user-1", fallback);

        assertEquals(2, result.size());
        assertEquals("gpt-4o", result.get(0).id());
        assertEquals("gpt-4o-mini", result.get(1).id());
        verify(mockAudit).recordMandatory(any(), any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testDiscoverModelsOllamaFormat() {
        String json = """
                {
                  "models": [
                    {"name": "llama3:latest"},
                    {"name": "mistral:latest"}
                  ]
                }
                """;
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(json);
        when(mockTransport.<String>send(any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(mockResponse);

        List<ModelEntry> result = service.discoverModels("ollama", "http://localhost:11434", null, "user-1", List.of());

        assertEquals(2, result.size());
        assertEquals("llama3:latest", result.get(0).id());
        assertEquals("mistral:latest", result.get(1).id());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testDiscoverModelsGeminiFormat() {
        String json = """
                {
                  "models": [
                    {"name": "models/gemini-2.5-flash", "displayName": "Gemini 2.5 Flash"},
                    {"name": "models/gemini-2.5-pro", "displayName": "Gemini 2.5 Pro"}
                  ]
                }
                """;
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(json);
        when(mockTransport.<String>send(any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(mockResponse);

        List<ModelEntry> result = service.discoverModels("gemini", null, "key-test", "user-1", List.of());

        assertEquals(2, result.size());
        assertEquals("gemini-2.5-flash", result.get(0).id());
        assertEquals("Gemini 2.5 Flash", result.get(0).label());
        assertEquals("gemini-2.5-pro", result.get(1).id());
        assertEquals("Gemini 2.5 Pro", result.get(1).label());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testDiscoverModelsAnthropicFormat() {
        String json = """
                {
                  "data": [
                    {"id": "claude-3-5-sonnet-20241022", "display_name": "Claude 3.5 Sonnet"}
                  ]
                }
                """;
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(json);
        when(mockTransport.<String>send(any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(mockResponse);

        List<ModelEntry> result = service.discoverModels("anthropic", null, "key-test", "user-1", List.of());

        assertEquals(1, result.size());
        assertEquals("claude-3-5-sonnet-20241022", result.get(0).id());
        assertEquals("Claude 3.5 Sonnet", result.get(0).label());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testDiscoverModelsFallbackOnError() {
        when(mockResponse.statusCode()).thenReturn(500);
        when(mockResponse.body()).thenReturn("Internal Server Error");
        when(mockTransport.<String>send(any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(mockResponse);

        List<ModelEntry> fallback = List.of(new ModelEntry("fallback-model", "Fallback Model"));
        List<ModelEntry> result = service.discoverModels("openai", null, "sk-test", "user-1", fallback);

        assertEquals(1, result.size());
        assertEquals("fallback-model", result.get(0).id());
    }

    @Test
    void testDiscoverModelsTransportNull_fallback() {
        service.governedTransport = null;
        List<ModelEntry> fallback = List.of(new ModelEntry("fallback-model", "Fallback Model"));
        List<ModelEntry> result = service.discoverModels("openai", null, "sk-test", "user-1", fallback);
        assertEquals(1, result.size());
        assertEquals("fallback-model", result.get(0).id());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testDiscoverModelsSendThrows_fallback() {
        when(mockTransport.<String>send(any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenThrow(new RuntimeException("Connection refused"));

        List<ModelEntry> fallback = List.of(new ModelEntry("fallback-model", "Fallback Model"));
        List<ModelEntry> result = service.discoverModels("openai", null, "sk-test", "user-1", fallback);
        assertEquals(1, result.size());
        assertEquals("fallback-model", result.get(0).id());
    }

    @Test
    void testDiscoverModelsBlockedHost_fallback() {
        List<ModelEntry> fallback = List.of(new ModelEntry("fallback-model", "Fallback Model"));
        List<ModelEntry> result =
                service.discoverModels("openai", "http://169.254.169.254:80", "sk-test", "user-1", fallback);
        assertEquals(1, result.size());
        assertEquals("fallback-model", result.get(0).id());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testDiscoverModelsCaching() {
        String json = """
                {
                  "data": [
                    {"id": "gpt-4o", "display_name": "GPT-4o"}
                  ]
                }
                """;
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(json);
        when(mockTransport.<String>send(any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(mockResponse);

        List<ModelEntry> first = service.discoverModels("openai", null, "sk-test", "user-1", List.of());
        assertEquals(1, first.size());

        List<ModelEntry> second = service.discoverModels("openai", null, "sk-test", "user-1", List.of());
        assertEquals(1, second.size());

        // Probe should only have been sent once due to caching
        verify(mockTransport, times(1)).send(any(), any(), any(), any(), anyBoolean(), any(), any());
    }

    @Test
    void resolversPreferTheDirectFieldOverTheInjectedInstance() {
        GovernedTransportService transport = mock(GovernedTransportService.class);
        AuditService audit = mock(AuditService.class);
        SecretVault vault = mock(SecretVault.class);
        ObjectMapper mapper = new ObjectMapper();

        service.governedTransport = transport;
        service.auditService = audit;
        service.vault = vault;
        service.objectMapper = mapper;

        assertSame(transport, service.resolveGovernedTransport());
        assertSame(audit, service.resolveAuditService());
        assertSame(vault, service.resolveVault());
        assertSame(mapper, service.resolveObjectMapper());
    }

    @Test
    void resolversFallThroughToResolvableInjectedInstances() {
        LiveProviderCatalogService s = new LiveProviderCatalogService();
        GovernedTransportService transport = mock(GovernedTransportService.class);
        AuditService audit = mock(AuditService.class);
        SecretVault vault = mock(SecretVault.class);
        ObjectMapper mapper = new ObjectMapper();
        s.governedTransportInstance = resolvable(transport);
        s.auditServiceInstance = resolvable(audit);
        s.vaultInstance = resolvable(vault);
        s.objectMapperInstance = resolvable(mapper);

        assertSame(transport, s.resolveGovernedTransport());
        assertSame(audit, s.resolveAuditService());
        assertSame(vault, s.resolveVault());
        assertSame(mapper, s.resolveObjectMapper());
    }

    @Test
    void resolversDegradeWhenTheInjectedInstancesAreNotResolvable() {
        LiveProviderCatalogService s = new LiveProviderCatalogService();
        s.governedTransportInstance = unresolvable();
        s.auditServiceInstance = unresolvable();
        s.vaultInstance = unresolvable();
        s.objectMapperInstance = unresolvable();

        assertNull(s.resolveGovernedTransport());
        assertNull(s.resolveAuditService());
        assertNull(s.resolveVault());
        assertNotNull(s.resolveObjectMapper(), "a usable mapper must still be supplied when none is available");
    }

    @Test
    void blankProviderIdReturnsTheCallerFallbackUnchanged() {
        List<ModelEntry> fallback = List.of(new ModelEntry("fallback-model", "Fallback"));

        assertSame(fallback, service.discoverModels("   ", null, null, null, fallback));
        assertEquals(List.of(), service.discoverModels(null, null, null, null, null));
        verifyNoProbe();
    }

    @Test
    void malformedCustomBaseUrlFallsBackWithoutProbing() {
        List<ModelEntry> fallback = List.of(new ModelEntry("fallback-model", "Fallback"));

        List<ModelEntry> result = service.discoverModels("openai", "http://[not-a-host", "sk-test", "user-1", fallback);

        assertSame(fallback, result);
        verifyNoProbe();
    }

    @Test
    void providerWithoutADefaultBaseUrlIsNotProbed() {
        List<ModelEntry> fallback = List.of(new ModelEntry("fallback-model", "Fallback"));

        List<ModelEntry> result = service.discoverModels("no-such-provider", null, "sk-test", "user-1", fallback);

        assertSame(fallback, result);
        verifyNoProbe();
    }

    @Test
    @SuppressWarnings("unchecked")
    void ollamaProbeCarriesNoAuthorizationHeaderEvenWhenAKeyIsPresent() {
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{\"models\":[{\"name\":\"llama3:latest\"}]}");
        when(mockTransport.<String>send(any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(mockResponse);

        // A host NAME, not a literal address: literal addresses (including loopback) are rejected
        // up front by the blocked-literal guard, so "localhost" is what reaches the probe.
        List<ModelEntry> result =
                service.discoverModels("ollama", "http://localhost:11434", "sk-ignored", "user-1", List.of());

        assertEquals(1, result.size());
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockTransport).send(any(), any(), any(), any(), anyBoolean(), captor.capture(), any());
        // The Ollama arm of applyAuthHeaders is deliberately empty: the local endpoint needs no auth.
        assertTrue(captor.getValue().headers().firstValue("Authorization").isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void blankProbeBodyYieldsNoModels() {
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("   ");
        when(mockTransport.<String>send(any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(mockResponse);

        List<ModelEntry> fallback = List.of(new ModelEntry("fallback-model", "Fallback"));

        assertSame(fallback, service.discoverModels("openai", null, "sk-test", "user-1", fallback));
    }

    @Test
    @SuppressWarnings("unchecked")
    void unparseableProbeBodyYieldsNoModels() {
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{not json");
        when(mockTransport.<String>send(any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(mockResponse);

        List<ModelEntry> fallback = List.of(new ModelEntry("fallback-model", "Fallback"));

        assertSame(fallback, service.discoverModels("openai", null, "sk-test", "user-1", fallback));
    }

    @Test
    @SuppressWarnings("unchecked")
    void apiKeyFallsBackToTheVaultWhenNoneWasSupplied() {
        SecretVault vault = mock(SecretVault.class);
        when(vault.retrieveSystemKey(ConfigKeys.OPENAI_API_KEY)).thenReturn(Optional.empty());
        when(vault.retrieveSystemKey("OPENAI_API_KEY"))
                .thenReturn(Optional.of(new VaultKey("vault-key".getBytes(StandardCharsets.UTF_8))));
        service.vault = vault;

        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{\"data\":[{\"id\":\"gpt-4o\"}]}");
        when(mockTransport.<String>send(any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(mockResponse);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        // "none" is the sentinel meaning "no key supplied", so the vault ladder is consulted instead.
        assertEquals(
                1,
                service.discoverModels("openai", null, "none", "user-1", List.of())
                        .size());
        verify(mockTransport).send(any(), any(), any(), any(), anyBoolean(), captor.capture(), any());
        assertEquals(
                "Bearer vault-key",
                captor.getValue().headers().firstValue("Authorization").orElse(null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingVaultKeyResolvesToNoApiKeyAtAll() {
        SecretVault vault = mock(SecretVault.class);
        when(vault.retrieveSystemKey(anyString())).thenReturn(Optional.empty());
        service.vault = vault;

        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{\"data\":[{\"id\":\"gpt-4o\"}]}");
        when(mockTransport.<String>send(any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(mockResponse);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        assertEquals(
                1,
                service.discoverModels("openai", null, "none", "user-1", List.of())
                        .size());
        verify(mockTransport).send(any(), any(), any(), any(), anyBoolean(), captor.capture(), any());
        assertTrue(captor.getValue().headers().firstValue("Authorization").isEmpty());
    }

    private void verifyNoProbe() {
        verify(mockTransport, never()).send(any(), any(), any(), any(), anyBoolean(), any(), any());
    }

    private static <T> Instance<T> resolvable(T value) {
        @SuppressWarnings("unchecked")
        Instance<T> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(value);
        return instance;
    }

    private static <T> Instance<T> unresolvable() {
        @SuppressWarnings("unchecked")
        Instance<T> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(false);
        return instance;
    }
}
