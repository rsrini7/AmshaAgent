package com.amsha.app.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.model.chat.ChatModel;

import javax.sql.DataSource;

import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import com.amsha.app.ui.dto.CockpitHealthResponse;
import com.amsha.llm.api.AgentChatModelProvider;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agroal.api.AgroalDataSource;
import io.agroal.api.AgroalDataSourceMetrics;
import io.agroal.api.configuration.AgroalConnectionPoolConfiguration;
import io.agroal.api.configuration.AgroalDataSourceConfiguration;

class CockpitHealthResourceTest {

    private CockpitHealthResource resource;
    private SecurityContext securityContext;
    private Instance<HealthCheck> healthChecksInstance;
    private Instance<DataSource> dataSourceInstance;
    private Instance<AgentChatModelProvider> providersInstance;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        resource = new CockpitHealthResource();
        securityContext = mock(SecurityContext.class);
        healthChecksInstance = mock(Instance.class);
        dataSourceInstance = mock(Instance.class);
        providersInstance = mock(Instance.class);

        resource.securityContext = securityContext;
        resource.healthChecks = healthChecksInstance;
        resource.dataSourceInstance = dataSourceInstance;
        resource.providers = providersInstance;
        resource.defaultProviderName = "groq";
        resource.runtimeBackend = "sqlite";
    }

    @Test
    void getHealthForbiddenForNonInternal() {
        when(securityContext.isUserInRole("internal")).thenReturn(false);

        Response response = resource.getHealth();
        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
    }

    @Test
    void getHealthAggregatesSmallRyeHealthAndPool() throws SQLException {
        when(securityContext.isUserInRole("internal")).thenReturn(true);

        HealthCheck hc1 = mock(HealthCheck.class);
        when(hc1.call())
                .thenReturn(HealthCheckResponse.named("Check1")
                        .up()
                        .withData("k", "v")
                        .build());

        HealthCheck hc2 = mock(HealthCheck.class);
        when(hc2.call())
                .thenReturn(HealthCheckResponse.named("Check2")
                        .down()
                        .withData("err", "timeout")
                        .build());

        when(healthChecksInstance.iterator())
                .thenAnswer(inv -> List.of(hc1, hc2).iterator());

        AgroalDataSource agroal = mock(AgroalDataSource.class);
        Connection conn = mock(Connection.class);
        when(conn.isValid(2)).thenReturn(true);
        when(agroal.getConnection()).thenReturn(conn);

        var agroalMetrics = mock(AgroalDataSourceMetrics.class);
        when(agroalMetrics.activeCount()).thenReturn(2L);
        when(agroalMetrics.availableCount()).thenReturn(3L);
        when(agroalMetrics.awaitingCount()).thenReturn(0L);
        when(agroal.getMetrics()).thenReturn(agroalMetrics);

        var config = mock(AgroalDataSourceConfiguration.class);
        var poolConfig = mock(AgroalConnectionPoolConfiguration.class);
        when(poolConfig.maxSize()).thenReturn(5);
        when(config.connectionPoolConfiguration()).thenReturn(poolConfig);
        when(agroal.getConfiguration()).thenReturn(config);

        when(dataSourceInstance.isResolvable()).thenReturn(true);
        when(dataSourceInstance.get()).thenReturn(agroal);

        AgentChatModelProvider groqProvider = mock(AgentChatModelProvider.class);
        when(groqProvider.providerName()).thenReturn("groq");
        when(groqProvider.baseUrl()).thenReturn("https://api.groq.com/openai/v1");
        when(groqProvider.create()).thenReturn(mock(ChatModel.class));

        AgentChatModelProvider openaiProvider = mock(AgentChatModelProvider.class);
        when(openaiProvider.providerName()).thenReturn("openai");
        when(openaiProvider.baseUrl()).thenReturn("https://api.openai.com/v1");
        when(openaiProvider.create()).thenThrow(new RuntimeException("Missing OPENAI_API_KEY"));

        when(providersInstance.iterator())
                .thenAnswer(inv -> List.of(openaiProvider, groqProvider).iterator());

        Response response = resource.getHealth();
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

        CockpitHealthResponse body = (CockpitHealthResponse) response.getEntity();
        assertEquals("DEGRADED", body.status()); // hc1 up, hc2 down -> partial check pass is DEGRADED
        assertEquals(2, body.healthChecks().totalChecks());
        assertEquals(1, body.healthChecks().passingChecks());

        assertTrue(body.database().poolEnabled());
        assertEquals("UP", body.database().status());
        assertEquals(2, body.database().activeConnections());
        assertEquals(3, body.database().availableConnections());
        assertEquals(5, body.database().maxPoolSize());

        assertEquals(2, body.providers().size());
        // default groq should come first
        assertEquals("groq", body.providers().get(0).providerName());
        assertTrue(body.providers().get(0).isDefault());
        assertEquals("CONFIGURED", body.providers().get(0).status());

        assertEquals("openai", body.providers().get(1).providerName());
        assertFalse(body.providers().get(1).isDefault());
        assertEquals("NOT_CONFIGURED", body.providers().get(1).status());
        assertEquals("api.openai.com", body.providers().get(1).baseUrl());
        assertEquals("MISSING_CREDENTIALS", body.providers().get(1).details());
        assertEquals("api.groq.com", body.providers().get(0).baseUrl());
        assertEquals("Ready (model instance created)", body.providers().get(0).details());
    }

    @Test
    void getHealthFilesystemBackend() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        resource.runtimeBackend = "filesystem";
        when(dataSourceInstance.isResolvable()).thenReturn(false);
        when(healthChecksInstance.iterator())
                .thenAnswer(inv -> List.<HealthCheck>of().iterator());
        when(providersInstance.iterator())
                .thenAnswer(inv -> List.<AgentChatModelProvider>of().iterator());

        Response response = resource.getHealth();
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

        CockpitHealthResponse body = (CockpitHealthResponse) response.getEntity();
        assertEquals("UP", body.status());
        assertEquals("filesystem", body.database().backend());
        assertEquals("UP", body.database().status());
        assertFalse(body.database().poolEnabled());
    }

    @Test
    void providerSummaryIsCachedAcrossRapidCalls() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        when(healthChecksInstance.iterator())
                .thenAnswer(inv -> List.<HealthCheck>of().iterator());
        when(dataSourceInstance.isResolvable()).thenReturn(false);
        resource.runtimeBackend = "filesystem";

        AgentChatModelProvider groqProvider = mock(AgentChatModelProvider.class);
        when(groqProvider.providerName()).thenReturn("groq");
        when(groqProvider.baseUrl()).thenReturn("https://api.groq.com/openai/v1");
        when(groqProvider.create()).thenReturn(mock(ChatModel.class));

        when(providersInstance.iterator())
                .thenAnswer(inv -> List.of(groqProvider).iterator());

        // Call twice rapidly
        Response resp1 = resource.getHealth();
        Response resp2 = resource.getHealth();

        assertEquals(Response.Status.OK.getStatusCode(), resp1.getStatus());
        assertEquals(Response.Status.OK.getStatusCode(), resp2.getStatus());
        // Verify groqProvider.create() was only called once due to 30s TTL cache
        verify(groqProvider, times(1)).create();
    }

    @Test
    void statusIsDownWhenAllHealthChecksFail() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        resource.runtimeBackend = "filesystem";
        when(dataSourceInstance.isResolvable()).thenReturn(false);

        HealthCheck hc = mock(HealthCheck.class);
        when(hc.call()).thenReturn(HealthCheckResponse.named("FailCheck").down().build());
        when(healthChecksInstance.iterator()).thenAnswer(inv -> List.of(hc).iterator());
        when(providersInstance.iterator())
                .thenAnswer(inv -> List.<AgentChatModelProvider>of().iterator());

        Response response = resource.getHealth();
        CockpitHealthResponse body = (CockpitHealthResponse) response.getEntity();
        assertEquals("DOWN", body.status());
    }

    @Test
    void statusIsDegradedWhenDefaultProviderNotConfigured() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        resource.runtimeBackend = "filesystem";
        when(dataSourceInstance.isResolvable()).thenReturn(false);
        when(healthChecksInstance.iterator())
                .thenAnswer(inv -> List.<HealthCheck>of().iterator());

        AgentChatModelProvider groqProvider = mock(AgentChatModelProvider.class);
        when(groqProvider.providerName()).thenReturn("groq");
        when(groqProvider.baseUrl()).thenReturn("https://api.groq.com/openai/v1");
        when(groqProvider.create()).thenThrow(new RuntimeException("API key missing"));
        when(providersInstance.iterator())
                .thenAnswer(inv -> List.of(groqProvider).iterator());

        Response response = resource.getHealth();
        CockpitHealthResponse body = (CockpitHealthResponse) response.getEntity();
        assertEquals("DEGRADED", body.status());
    }

    @Test
    void databaseSummaryIsCachedAcrossRapidCalls() throws SQLException {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        when(healthChecksInstance.iterator())
                .thenAnswer(inv -> List.<HealthCheck>of().iterator());
        when(providersInstance.iterator())
                .thenAnswer(inv -> List.<AgentChatModelProvider>of().iterator());

        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        when(conn.isValid(2)).thenReturn(true);
        when(ds.getConnection()).thenReturn(conn);

        when(dataSourceInstance.isResolvable()).thenReturn(true);
        when(dataSourceInstance.get()).thenReturn(ds);

        Response resp1 = resource.getHealth();
        Response resp2 = resource.getHealth();

        assertEquals(Response.Status.OK.getStatusCode(), resp1.getStatus());
        assertEquals(Response.Status.OK.getStatusCode(), resp2.getStatus());
        verify(conn, times(1)).isValid(2);
    }

    @Test
    void statusIsDownWhenDatabaseNotConfigured() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        resource.runtimeBackend = "sqlite";
        when(dataSourceInstance.isResolvable()).thenReturn(false);
        when(healthChecksInstance.iterator())
                .thenAnswer(inv -> List.<HealthCheck>of().iterator());
        when(providersInstance.iterator())
                .thenAnswer(inv -> List.<AgentChatModelProvider>of().iterator());

        Response response = resource.getHealth();
        CockpitHealthResponse body = (CockpitHealthResponse) response.getEntity();
        assertEquals("DOWN", body.status());
        assertEquals("NOT_CONFIGURED", body.database().status());
    }

    @Test
    void healthCheckDataIsSanitizedAndCategorized() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        resource.runtimeBackend = "filesystem";
        when(dataSourceInstance.isResolvable()).thenReturn(false);
        when(providersInstance.iterator())
                .thenAnswer(inv -> List.<AgentChatModelProvider>of().iterator());

        HealthCheck safeHc = mock(HealthCheck.class);
        when(safeHc.call())
                .thenReturn(HealthCheckResponse.named("safe-check")
                        .up()
                        .withData("version", "1.2.3")
                        .withData("backend", "sqlite")
                        .withData("secretKey", "super-secret-value-12345")
                        .withData("jdbcUrl", "jdbc:sqlite:/secret/db.sqlite")
                        .build());

        class TimeoutCheck implements HealthCheck {
            @Override
            public HealthCheckResponse call() {
                throw new RuntimeException("connection timeout");
            }
        }

        class SecCheck implements HealthCheck {
            @Override
            public HealthCheckResponse call() {
                throw new SecurityException("access denied");
            }
        }

        class GenericCheck implements HealthCheck {
            @Override
            public HealthCheckResponse call() {
                throw new IllegalStateException("internal fault");
            }
        }

        when(healthChecksInstance.iterator())
                .thenAnswer(inv -> List.of(safeHc, new TimeoutCheck(), new SecCheck(), new GenericCheck())
                        .iterator());

        Response response = resource.getHealth();
        CockpitHealthResponse body = (CockpitHealthResponse) response.getEntity();

        var checks = body.healthChecks().checks();
        assertEquals(4, checks.size());

        var safeDetail = checks.stream()
                .filter(c -> c.name().equals("safe-check"))
                .findFirst()
                .orElseThrow();
        assertEquals("UP", safeDetail.status());
        assertTrue(safeDetail.data().containsKey("version"));
        assertTrue(safeDetail.data().containsKey("backend"));
        assertFalse(safeDetail.data().containsKey("secretKey"));
        assertFalse(safeDetail.data().containsKey("jdbcUrl"));

        var timeoutDetail = checks.stream()
                .filter(c -> c.name().contains("TimeoutCheck"))
                .findFirst()
                .orElseThrow();
        assertEquals("DOWN", timeoutDetail.status());
        assertEquals("TIMEOUT", timeoutDetail.data().get("errorCategory"));

        var secDetail = checks.stream()
                .filter(c -> c.name().contains("SecCheck"))
                .findFirst()
                .orElseThrow();
        assertEquals("DOWN", secDetail.status());
        assertEquals("SECURITY_ERROR", secDetail.data().get("errorCategory"));

        var genericDetail = checks.stream()
                .filter(c -> c.name().contains("GenericCheck"))
                .findFirst()
                .orElseThrow();
        assertEquals("DOWN", genericDetail.status());
        assertEquals("CHECK_FAILED", genericDetail.data().get("errorCategory"));
    }

    @Test
    void providerBaseUrlRedactionAndErrorCategorization() {
        assertEquals("localhost:11434", CockpitHealthResource.sanitizeBaseUrl("http://localhost:11434/api/generate"));
        assertEquals("api.groq.com", CockpitHealthResource.sanitizeBaseUrl("https://api.groq.com/openai/v1"));
        assertEquals(
                "internal.host:8443",
                CockpitHealthResource.sanitizeBaseUrl("https://user:pass@internal.host:8443/v1?token=xyz"));
        assertEquals("", CockpitHealthResource.sanitizeBaseUrl(null));
        assertEquals("", CockpitHealthResource.sanitizeBaseUrl("   "));

        assertEquals(
                "MISSING_CREDENTIALS",
                CockpitHealthResource.categorizeProviderError(new RuntimeException("invalid api key")));
        assertEquals(
                "MISSING_CREDENTIALS",
                CockpitHealthResource.categorizeProviderError(new RuntimeException("token unauthorized")));
        assertEquals(
                "UNSUPPORTED_PROVIDER",
                CockpitHealthResource.categorizeProviderError(new RuntimeException("unsupported provider model")));
        assertEquals(
                "CONFIGURATION_ERROR",
                CockpitHealthResource.categorizeProviderError(new IllegalArgumentException("bad config param")));
        assertEquals(
                "INITIALIZATION_FAILED", CockpitHealthResource.categorizeProviderError(new NullPointerException()));
    }
}
