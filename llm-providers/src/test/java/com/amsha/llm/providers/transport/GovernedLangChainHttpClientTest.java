package com.amsha.llm.providers.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.amsha.core.scope.WorkspaceContext;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.http.GovernedTransportService;
import com.amsha.security.http.OutboundHttpException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

@Isolated
class GovernedLangChainHttpClientTest {

    private GovernedTransportService transportService;
    private GovernedLangChainHttpClient client;

    @BeforeEach
    void setUp() {
        transportService = mock(GovernedTransportService.class);
        client = new GovernedLangChainHttpClient(Duration.ofSeconds(10), Duration.ofSeconds(30), transportService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void executePostRoutesThroughGovernedTransportSend() throws Exception {
        HttpResponse<String> mockResp = mock(HttpResponse.class);
        when(mockResp.statusCode()).thenReturn(200);
        when(mockResp.body()).thenReturn("{\"result\":\"ok\"}");
        HttpHeaders mockHeaders = HttpHeaders.of(Map.of("content-type", List.of("application/json")), (k, v) -> true);
        when(mockResp.headers()).thenReturn(mockHeaders);

        Mockito.doReturn(mockResp)
                .when(transportService)
                .send(
                        any(),
                        eq(OutboundPurpose.PROVIDER_API_CALL),
                        any(),
                        any(),
                        eq(true),
                        any(java.net.http.HttpRequest.class), // fully qualified to disambiguate
                        any());

        HttpRequest req = HttpRequest.builder()
                .url("https://api.openai.com/v1/chat/completions")
                .method(HttpMethod.POST)
                .headers(Map.of(
                        "Authorization", List.of("Bearer test-key"),
                        "Content-Length", List.of("123"), // restricted header to filter
                        "Host", List.of("api.openai.com"), // restricted header to filter
                        "Custom-Header", List.of("val1")))
                .body("{\"model\":\"gpt-4o\",\"messages\":[]}")
                .build();

        SuccessfulHttpResponse resp = WorkspaceContext.callWithActiveWorkspace(
                "ws-1",
                () -> ScopedValue.where(WorkspaceContext.USER_ID, "test-operator")
                        .call(() -> client.execute(req)));

        assertNotNull(resp);
        assertEquals(200, resp.statusCode());
        assertEquals("{\"result\":\"ok\"}", resp.body());

        ArgumentCaptor<java.net.http.HttpRequest> reqCaptor = // fully qualified to disambiguate
                ArgumentCaptor.forClass(java.net.http.HttpRequest.class); // fully qualified to disambiguate
        verify(transportService)
                .send(
                        eq("https://api.openai.com/v1/chat/completions"),
                        eq(OutboundPurpose.PROVIDER_API_CALL),
                        any(),
                        eq("test-operator"),
                        eq(true),
                        reqCaptor.capture(),
                        any());

        java.net.http.HttpRequest sentHttpReq = reqCaptor.getValue(); // fully qualified to disambiguate
        assertEquals("POST", sentHttpReq.method());
        assertEquals(
                "https://api.openai.com/v1/chat/completions", sentHttpReq.uri().toString());
        assertTrue(sentHttpReq.headers().firstValue("Authorization").isPresent());
        assertEquals(
                "Bearer test-key",
                sentHttpReq.headers().firstValue("Authorization").get());
        assertTrue(sentHttpReq.headers().firstValue("Custom-Header").isPresent());
        // Restricted headers should have been filtered
        assertFalse(sentHttpReq.headers().firstValue("Content-Length").isPresent());
        assertFalse(sentHttpReq.headers().firstValue("Host").isPresent());
    }

    @Test
    @SuppressWarnings("unchecked")
    void non2xxStatusCodeThrowsHttpException() {
        HttpResponse<String> mockResp = mock(HttpResponse.class);
        when(mockResp.statusCode()).thenReturn(401);
        when(mockResp.body()).thenReturn("{\"error\":\"unauthorized\"}");
        HttpHeaders mockHeaders = HttpHeaders.of(Map.of(), (k, v) -> true);
        when(mockResp.headers()).thenReturn(mockHeaders);

        Mockito.doReturn(mockResp).when(transportService).send(any(), any(), any(), any(), anyBoolean(), any(), any());

        HttpRequest req = HttpRequest.builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .method(HttpMethod.POST)
                .body("{}")
                .build();

        HttpException ex = assertThrows(HttpException.class, () -> client.execute(req));
        assertEquals(401, ex.statusCode());
        assertTrue(ex.getMessage().contains("unauthorized"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamingSseInvokesListenerAndParser() {
        HttpResponse<InputStream> mockResp = mock(HttpResponse.class);
        when(mockResp.statusCode()).thenReturn(200);
        String ssePayload = "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n";
        when(mockResp.body()).thenReturn(new ByteArrayInputStream(ssePayload.getBytes(StandardCharsets.UTF_8)));
        HttpHeaders mockHeaders = HttpHeaders.of(Map.of(), (k, v) -> true);
        when(mockResp.headers()).thenReturn(mockHeaders);

        Mockito.doReturn(mockResp).when(transportService).send(any(), any(), any(), any(), anyBoolean(), any(), any());

        HttpRequest req = HttpRequest.builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .method(HttpMethod.POST)
                .body("{\"stream\":true}")
                .build();

        AtomicBoolean opened = new AtomicBoolean(false);
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicBoolean parsed = new AtomicBoolean(false);

        ServerSentEventListener listener = new ServerSentEventListener() {
            @Override
            public void onOpen(SuccessfulHttpResponse response) {
                opened.set(true);
            }

            @Override
            public void onEvent(ServerSentEvent event) {}

            @Override
            public void onError(Throwable error) {}

            @Override
            public void onClose() {
                closed.set(true);
            }
        };

        ServerSentEventParser parser = (is, l) -> parsed.set(true);

        client.execute(req, parser, listener);

        assertTrue(opened.get());
        assertTrue(parsed.get());
        assertTrue(closed.get());
    }

    @Test
    void missingGovernedTransportFailsClosed() {
        GovernedLangChainHttpClient unconfiguredClient =
                new GovernedLangChainHttpClient(Duration.ofSeconds(5), Duration.ofSeconds(5), null);

        HttpRequest req = HttpRequest.builder()
                .url("https://api.openai.com/v1/chat/completions")
                .method(HttpMethod.POST)
                .body("{}")
                .build();

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> unconfiguredClient.execute(req));
        assertTrue(ex.getMessage().contains("Governed outbound transport"));
    }

    @Test
    void ensureConfiguredOverwritesConflictingClientBuilderFactoryProperty() {
        String original = System.getProperty(GovernedLangChainHttpClientBuilderFactory.FACTORY_PROPERTY);
        try {
            System.setProperty(
                    GovernedLangChainHttpClientBuilderFactory.FACTORY_PROPERTY,
                    "io.quarkiverse.langchain4j.jaxrsclient.JaxRsHttpClientBuilderFactory");
            assertEquals(
                    "io.quarkiverse.langchain4j.jaxrsclient.JaxRsHttpClientBuilderFactory",
                    System.getProperty(GovernedLangChainHttpClientBuilderFactory.FACTORY_PROPERTY));

            GovernedLangChainHttpClientBuilderFactory.ensureConfigured();

            assertEquals(
                    GovernedLangChainHttpClientBuilderFactory.class.getName(),
                    System.getProperty(GovernedLangChainHttpClientBuilderFactory.FACTORY_PROPERTY));
        } finally {
            if (original != null) {
                System.setProperty(GovernedLangChainHttpClientBuilderFactory.FACTORY_PROPERTY, original);
            } else {
                System.clearProperty(GovernedLangChainHttpClientBuilderFactory.FACTORY_PROPERTY);
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void malformedUrlInSseInvokesListenerOnErrorAndSingleClose() {
        HttpRequest req = HttpRequest.builder()
                .url("http://invalid url with spaces and characters:<>")
                .method(HttpMethod.POST)
                .body("{}")
                .build();

        AtomicBoolean errored = new AtomicBoolean(false);
        AtomicBoolean closed = new AtomicBoolean(false);

        ServerSentEventListener listener = new ServerSentEventListener() {
            @Override
            public void onOpen(SuccessfulHttpResponse response) {}

            @Override
            public void onEvent(ServerSentEvent event) {}

            @Override
            public void onError(Throwable error) {
                errored.set(true);
            }

            @Override
            public void onClose() {
                closed.set(true);
            }
        };

        ServerSentEventParser parser = (is, l) -> {};
        client.execute(req, parser, listener);

        assertTrue(errored.get());
        assertTrue(closed.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void sseStreamTimeoutTriggersErrorAndSingleClose() {
        GovernedLangChainHttpClient timedClient =
                new GovernedLangChainHttpClient(Duration.ofMillis(50), Duration.ofMillis(50), transportService);

        InputStream stallingStream = new InputStream() {
            private volatile boolean closed = false;

            @Override
            public int read() throws IOException {
                while (!closed) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                throw new IOException("Stream closed");
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return read();
            }

            @Override
            public void close() {
                closed = true;
            }
        };

        HttpResponse<InputStream> mockResp = mock(HttpResponse.class);
        when(mockResp.statusCode()).thenReturn(200);
        when(mockResp.body()).thenReturn(stallingStream);
        HttpHeaders mockHeaders = HttpHeaders.of(Map.of(), (k, v) -> true);
        when(mockResp.headers()).thenReturn(mockHeaders);

        Mockito.doReturn(mockResp).when(transportService).send(any(), any(), any(), any(), anyBoolean(), any(), any());

        HttpRequest req = HttpRequest.builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .method(HttpMethod.POST)
                .body("{\"stream\":true}")
                .build();

        AtomicBoolean errored = new AtomicBoolean(false);
        AtomicInteger closeCount = new AtomicInteger(0);

        ServerSentEventListener listener = new ServerSentEventListener() {
            @Override
            public void onOpen(SuccessfulHttpResponse response) {}

            @Override
            public void onEvent(ServerSentEvent event) {}

            @Override
            public void onError(Throwable error) {
                errored.set(true);
            }

            @Override
            public void onClose() {
                closeCount.incrementAndGet();
            }
        };

        ServerSentEventParser parser = (is, l) -> {
            try {
                is.read();
            } catch (IOException e) {
                l.onError(e);
            }
        };

        timedClient.execute(req, parser, listener);

        assertTrue(errored.get());
        assertEquals(1, closeCount.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeTimeoutThrowsOutboundHttpException() {
        when(transportService.send(any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenThrow(OutboundHttpException.timeout("read timed out"));

        HttpRequest req = HttpRequest.builder()
                .url("https://api.openai.com/v1/chat/completions")
                .method(HttpMethod.POST)
                .body("{}")
                .build();

        OutboundHttpException ex = assertThrows(OutboundHttpException.class, () -> client.execute(req));
        assertEquals(OutboundHttpException.Category.TIMEOUT, ex.category());
        assertTrue(ex.getMessage().contains("read timed out"));
    }
}
