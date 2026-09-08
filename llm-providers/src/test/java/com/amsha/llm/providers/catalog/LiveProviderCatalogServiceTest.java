package com.amsha.llm.providers.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.http.HttpResponse;
import java.util.List;

import com.amsha.core.audit.AuditService;
import com.amsha.llm.api.ModelEntry;
import com.amsha.security.http.GovernedTransportService;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
}
