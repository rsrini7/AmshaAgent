package com.amsha.llm.providers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.amsha.core.routing.LlmModelFactory;
import com.amsha.core.security.SecretVault;
import com.amsha.core.security.VaultKey;
import com.amsha.core.security.http.GovernedTransportService;
import com.amsha.llm.api.AgentLlmConfig;
import com.amsha.llm.api.RoutingChatModelProvider;
import com.amsha.llm.api.StructuredOutputMode;

import io.quarkus.test.Mock;

/**
 * Test producers providing mocks and functional delegators for core CDI dependencies in llm-providers tests.
 */
@ApplicationScoped
public class TestBeanProducer {

    @Produces
    @Mock
    @ApplicationScoped
    public SecretVault secretVault() {
        Map<String, byte[]> store = new ConcurrentHashMap<>();
        SecretVault vault = mock(SecretVault.class);

        doAnswer(inv -> {
                    String key = inv.getArgument(0);
                    byte[] bytes = inv.getArgument(1);
                    store.put(key, bytes.clone());
                    return null;
                })
                .when(vault)
                .storeSystemKey(anyString(), any());

        doAnswer(inv -> {
                    String key = inv.getArgument(0);
                    byte[] bytes = store.get(key);
                    if (bytes == null) {
                        return Optional.empty();
                    }
                    return Optional.of(new VaultKey(bytes.clone()));
                })
                .when(vault)
                .retrieveSystemKey(anyString());

        return vault;
    }

    @Produces
    @Mock
    @ApplicationScoped
    public GovernedTransportService governedTransportService() {
        GovernedTransportService service = mock(GovernedTransportService.class);
        HttpClient client = HttpClient.newHttpClient();

        when(service.send(
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        anyBoolean(),
                        any(HttpRequest.class),
                        any(HttpResponse.BodyHandler.class)))
                .thenAnswer(inv -> {
                    HttpRequest req = inv.getArgument(5);
                    HttpResponse.BodyHandler<Object> handler = inv.getArgument(6);
                    try {
                        return client.send(req, handler);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

        return service;
    }

    @Produces
    @Mock
    @ApplicationScoped
    public AgentLlmConfig agentLlmConfig() {
        AgentLlmConfig config = mock(AgentLlmConfig.class);
        when(config.structuredOutputMode()).thenReturn(StructuredOutputMode.NATIVE);
        return config;
    }

    @Produces
    @ApplicationScoped
    public LlmModelFactory llmModelFactory(@Any Instance<RoutingChatModelProvider> factories) {
        return new LlmModelFactory(factories);
    }
}
