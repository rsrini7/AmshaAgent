package com.amsha.llm.providers.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.core.scope.WorkspaceContext;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.http.GovernedTransportService;
import com.amsha.security.http.OutboundHttpException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InstanceHandle;

@Isolated
class GovernedLangChainHttpClientTest {

    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";
    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";

    private GovernedTransportService transportService;
    private GovernedLangChainHttpClient client;

    @BeforeEach
    void setUp() {
        transportService = mock(GovernedTransportService.class);
        client = new GovernedLangChainHttpClient(Duration.ofSeconds(10), Duration.ofSeconds(30), transportService);
        // The factory default is process-wide static state; clear it so each test starts from the same place.
        GovernedLangChainHttpClientBuilderFactory.setDefaultTransport(null);
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
                .url(OPENAI_URL)
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
                        eq(OPENAI_URL),
                        eq(OutboundPurpose.PROVIDER_API_CALL),
                        any(),
                        eq("test-operator"),
                        eq(true),
                        reqCaptor.capture(),
                        any());

        java.net.http.HttpRequest sentHttpReq = reqCaptor.getValue(); // fully qualified to disambiguate
        assertEquals("POST", sentHttpReq.method());
        assertEquals(OPENAI_URL, sentHttpReq.uri().toString());
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
        stubStringResponse(transportService, 401, "{\"error\":\"unauthorized\"}");

        HttpException ex = assertThrows(HttpException.class, () -> client.execute(post(GROQ_URL)));
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
                .url(GROQ_URL)
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

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> unconfiguredClient.execute(post(OPENAI_URL)));
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
                .url(GROQ_URL)
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

        OutboundHttpException ex = assertThrows(OutboundHttpException.class, () -> client.execute(post(OPENAI_URL)));
        assertEquals(OutboundHttpException.Category.TIMEOUT, ex.category());
        assertTrue(ex.getMessage().contains("read timed out"));
    }

    // ---- execute(HttpRequest): a checked transport failure is wrapped, not rethrown ----

    @Test
    @SuppressWarnings("unchecked")
    void executeWrapsACheckedTransportFailureAsRuntimeException() {
        // send(...) declares no checked exceptions, so the failure has to arrive through an Answer.
        doAnswer(invocation -> {
                    throw new IOException("tls handshake failed");
                })
                .when(transportService)
                .send(any(), any(), any(), any(), anyBoolean(), any(), any());

        RuntimeException ex = assertThrows(RuntimeException.class, () -> client.execute(post(OPENAI_URL)));

        assertFalse(ex instanceof OutboundHttpException, "only unchecked governed failures are rethrown as-is");
        assertTrue(ex.getMessage().contains("Governed provider HTTP request failed"));
        assertTrue(ex.getMessage().contains("tls handshake failed"));
    }

    // ---- streaming: a non-2xx response is reported to the listener and closed once ----

    @Test
    @SuppressWarnings("unchecked")
    void streamingNon2xxNotifiesErrorAndClosesExactlyOnce() {
        HttpResponse<InputStream> mockResp = mock(HttpResponse.class);
        when(mockResp.statusCode()).thenReturn(503);
        when(mockResp.body())
                .thenReturn(new ByteArrayInputStream("upstream unavailable".getBytes(StandardCharsets.UTF_8)));
        when(mockResp.headers()).thenReturn(HttpHeaders.of(Map.of(), (k, v) -> true));
        Mockito.doReturn(mockResp).when(transportService).send(any(), any(), any(), any(), anyBoolean(), any(), any());

        RecordingSseListener listener = new RecordingSseListener();

        client.execute(
                post(GROQ_URL),
                (is, l) -> {
                    throw new AssertionError("the parser must not run for a non-2xx response");
                },
                listener);

        assertFalse(listener.opened.get(), "onOpen must not fire for a non-2xx response");
        HttpException ex = (HttpException) listener.error.get();
        assertEquals(503, ex.statusCode());
        assertTrue(ex.getMessage().contains("upstream unavailable"));
        assertEquals(1, listener.closeCount.get(), "safeClose must run exactly once");
    }

    // ---- resolveTransport(): the process-wide factory default, then CDI ----

    @Test
    @SuppressWarnings("unchecked")
    void resolveTransportFallsBackToTheFactoryDefaultTransport() {
        GovernedTransportService defaultTransport = mock(GovernedTransportService.class);
        stubStringResponse(defaultTransport, 200, "{}");
        GovernedLangChainHttpClientBuilderFactory.setDefaultTransport(defaultTransport);
        try {
            GovernedLangChainHttpClient withoutInjectedTransport =
                    new GovernedLangChainHttpClient(Duration.ofSeconds(5), Duration.ofSeconds(5), null);

            assertEquals(200, withoutInjectedTransport.execute(post(OPENAI_URL)).statusCode());
            verify(defaultTransport).send(any(), any(), any(), any(), anyBoolean(), any(), any());
        } finally {
            GovernedLangChainHttpClientBuilderFactory.setDefaultTransport(null);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveTransportUsesTheCdiContainerWhenNothingElseIsWired() {
        GovernedTransportService fromCdi = mock(GovernedTransportService.class);
        stubStringResponse(fromCdi, 200, "{}");
        InstanceHandle<GovernedTransportService> handle = mock(InstanceHandle.class);
        when(handle.isAvailable()).thenReturn(true);
        when(handle.get()).thenReturn(fromCdi);
        ArcContainer container = mock(ArcContainer.class);
        when(container.instance(GovernedTransportService.class)).thenReturn(handle);

        try (MockedStatic<Arc> arc = Mockito.mockStatic(Arc.class)) {
            arc.when(Arc::container).thenReturn(container);

            assertEquals(
                    200,
                    clientWithoutInjectedTransport().execute(post(OPENAI_URL)).statusCode());
        }
        verify(fromCdi).send(any(), any(), any(), any(), anyBoolean(), any(), any());
    }

    @Test
    void resolveTransportFailsClosedWhenCdiIsAbsent() {
        try (MockedStatic<Arc> arc = Mockito.mockStatic(Arc.class)) {
            arc.when(Arc::container).thenThrow(new NoClassDefFoundError("io/quarkus/arc/Arc"));

            IllegalStateException ex = assertThrows(
                    IllegalStateException.class,
                    () -> clientWithoutInjectedTransport().execute(post(OPENAI_URL)));

            assertTrue(ex.getMessage().contains("Governed outbound transport"));
        }
    }

    @Test
    void resolveTransportFailsClosedWhenTheCdiContainerThrows() {
        try (MockedStatic<Arc> arc = Mockito.mockStatic(Arc.class)) {
            arc.when(Arc::container).thenThrow(new IllegalStateException("arc container not started"));

            IllegalStateException ex = assertThrows(
                    IllegalStateException.class,
                    () -> clientWithoutInjectedTransport().execute(post(OPENAI_URL)));

            assertTrue(ex.getMessage().contains("Failed to resolve GovernedTransportService from CDI container"));
            assertTrue(ex.getMessage().contains("arc container not started"));
        }
    }

    // ---- buildHttpRequest(): the remaining HTTP methods and header filtering ----

    @Test
    @SuppressWarnings("unchecked")
    void executeTranslatesGetAndDeleteRequests() {
        stubStringResponse(transportService, 200, "{}");
        ArgumentCaptor<java.net.http.HttpRequest> captor = ArgumentCaptor.forClass(java.net.http.HttpRequest.class);

        client.execute(HttpRequest.builder()
                .url("https://api.openai.com/v1/models")
                .method(HttpMethod.GET)
                .build());
        client.execute(HttpRequest.builder()
                .url("https://api.openai.com/v1/files/file-1")
                .method(HttpMethod.DELETE)
                .build());

        verify(transportService, times(2)).send(any(), any(), any(), any(), anyBoolean(), captor.capture(), any());
        assertEquals("GET", captor.getAllValues().get(0).method());
        assertEquals("DELETE", captor.getAllValues().get(1).method());
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeDropsAHeaderWithABlankName() {
        stubStringResponse(transportService, 200, "{}");

        HttpRequest req = HttpRequest.builder()
                .url(OPENAI_URL)
                .method(HttpMethod.POST)
                .headers(Map.of("", List.of("ignored")))
                .body("{}")
                .build();

        assertEquals(200, client.execute(req).statusCode());

        ArgumentCaptor<java.net.http.HttpRequest> captor = ArgumentCaptor.forClass(java.net.http.HttpRequest.class);
        verify(transportService).send(any(), any(), any(), any(), anyBoolean(), captor.capture(), any());
        assertTrue(
                captor.getValue().headers().map().keySet().stream().noneMatch(String::isBlank),
                "a blank header name must be filtered out, not forwarded");
    }

    // ---- WatchdogInputStream: the read paths and the timeout conversion ----

    @Test
    void watchdogReadsSingleBytesAndReportsEof() throws IOException {
        try (GovernedLangChainHttpClient.WatchdogInputStream is = new GovernedLangChainHttpClient.WatchdogInputStream(
                new ByteArrayInputStream(new byte[] {42}), Duration.ofSeconds(30))) {
            assertEquals(42, is.read());
            assertEquals(-1, is.read());
        }
    }

    @Test
    void watchdogRethrowsAPlainReadFailureWhenItHasNotTimedOut() throws IOException {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("pipe broken");
            }
        };

        try (GovernedLangChainHttpClient.WatchdogInputStream is =
                new GovernedLangChainHttpClient.WatchdogInputStream(broken, Duration.ofSeconds(30))) {
            IOException ex = assertThrows(IOException.class, () -> is.read());

            assertEquals("pipe broken", ex.getMessage());
            assertFalse(ex instanceof HttpTimeoutException, "no timeout has fired, so the original failure stands");
        }
    }

    @Test
    void watchdogRethrowsAnArrayReadFailureWhenItHasNotTimedOut() throws IOException {
        InputStream broken = new InputStream() {
            @Override
            public int read() {
                return -1;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                throw new IOException("pipe broken");
            }
        };

        try (GovernedLangChainHttpClient.WatchdogInputStream is =
                new GovernedLangChainHttpClient.WatchdogInputStream(broken, Duration.ofSeconds(30))) {
            IOException ex = assertThrows(IOException.class, () -> is.read(new byte[4], 0, 4));

            assertEquals("pipe broken", ex.getMessage());
            assertFalse(ex instanceof HttpTimeoutException, "no timeout has fired, so the original failure stands");
        }
    }

    @Test
    void watchdogReadsIntoAnArray() throws IOException {
        try (GovernedLangChainHttpClient.WatchdogInputStream is = new GovernedLangChainHttpClient.WatchdogInputStream(
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)), Duration.ofSeconds(30))) {
            byte[] buffer = new byte[8];

            assertEquals(5, is.read(buffer, 0, buffer.length));
            assertEquals("hello", new String(buffer, 0, 5, StandardCharsets.UTF_8));
        }
    }

    @Test
    void watchdogTurnsAnArrayReadFailureIntoATimeoutOnceItHasFired() throws Exception {
        CountDownLatch closedByWatchdog = new CountDownLatch(1);
        InputStream stalling = new InputStream() {
            @Override
            public int read() {
                return -1;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                throw new IOException("pipe broken");
            }

            @Override
            public void close() {
                closedByWatchdog.countDown();
            }
        };

        GovernedLangChainHttpClient.WatchdogInputStream is =
                new GovernedLangChainHttpClient.WatchdogInputStream(stalling, Duration.ofMillis(1));
        // Wait for the watchdog so `timedOut` is already set when the read fails.
        assertTrue(closedByWatchdog.await(5, TimeUnit.SECONDS), "the watchdog never fired");
        try {
            HttpTimeoutException ex = assertThrows(HttpTimeoutException.class, () -> is.read(new byte[4], 0, 4));

            assertTrue(ex.getMessage().contains("SSE stream read timed out after"));
        } finally {
            is.close();
        }
    }

    @Test
    void watchdogClosesTheUnderlyingStreamOnTimeoutEvenWhenThatCloseFails() throws Exception {
        CountDownLatch closeAttempted = new CountDownLatch(1);
        InputStream closeFails = new InputStream() {
            @Override
            public int read() {
                return -1;
            }

            @Override
            public void close() throws IOException {
                closeAttempted.countDown();
                throw new IOException("close failed");
            }
        };

        GovernedLangChainHttpClient.WatchdogInputStream is =
                new GovernedLangChainHttpClient.WatchdogInputStream(closeFails, Duration.ofMillis(1));
        // onTimeout swallows the close failure; the latch proves the attempt still happened.
        assertTrue(closeAttempted.await(5, TimeUnit.SECONDS), "the watchdog never tried to close the stream");
        assertThrows(IOException.class, is::close);
    }

    // ---- helpers ----

    private static HttpRequest post(String url) {
        return HttpRequest.builder().url(url).method(HttpMethod.POST).body("{}").build();
    }

    private GovernedLangChainHttpClient clientWithoutInjectedTransport() {
        GovernedLangChainHttpClientBuilderFactory.setDefaultTransport(null);
        return new GovernedLangChainHttpClient(Duration.ofSeconds(5), Duration.ofSeconds(5), null);
    }

    @SuppressWarnings("unchecked")
    private static void stubStringResponse(GovernedTransportService transport, int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (k, v) -> true));
        Mockito.doReturn(response).when(transport).send(any(), any(), any(), any(), anyBoolean(), any(), any());
    }

    /** Records the SSE callbacks a test cares about. */
    private static final class RecordingSseListener implements ServerSentEventListener {

        private final AtomicBoolean opened = new AtomicBoolean(false);
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private final AtomicInteger closeCount = new AtomicInteger(0);

        @Override
        public void onOpen(SuccessfulHttpResponse response) {
            opened.set(true);
        }

        @Override
        public void onEvent(ServerSentEvent event) {
            // not under test
        }

        @Override
        public void onError(Throwable t) {
            error.set(t);
        }

        @Override
        public void onClose() {
            closeCount.incrementAndGet();
        }
    }
}
