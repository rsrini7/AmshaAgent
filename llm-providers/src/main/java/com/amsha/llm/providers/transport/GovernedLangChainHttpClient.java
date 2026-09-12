package com.amsha.llm.providers.transport;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.amsha.common.util.LogUtil;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.http.GovernedTransportService;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InstanceHandle;

/**
 * LangChain4j {@link HttpClient} implementation that routes all outbound provider HTTP requests
 * and Server-Sent Events through {@link GovernedTransportService#send}.
 *
 * <p>Enforces SSRF, TLS, DNS revalidation, redirect-hop, proxy, timeout, credential leakage
 * prevention, and mandatory audit logging on every byte transmitted by LLM provider SDKs.</p>
 */
public class GovernedLangChainHttpClient implements HttpClient {

    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final GovernedTransportService governedTransport;

    public GovernedLangChainHttpClient(
            Duration connectTimeout, Duration readTimeout, GovernedTransportService governedTransport) {
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.governedTransport = governedTransport;
    }

    private static final ScheduledExecutorService SSE_WATCHDOG_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sse-watchdog");
                t.setDaemon(true);
                return t;
            });

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) {
        GovernedTransportService transport = resolveTransport();
        String actorUserId = WorkspaceContext.getUserIdOrUnknown();

        HttpResponse<String> response;
        try {
            java.net.http.HttpRequest httpRequest = buildHttpRequest(request);
            response = transport.send(
                    request.url(),
                    OutboundPurpose.PROVIDER_API_CALL,
                    null,
                    actorUserId,
                    true,
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Governed provider HTTP request failed: " + LogUtil.rootMessage(e), e);
        }

        String body = response.body() != null ? response.body() : "";
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return SuccessfulHttpResponse.builder()
                    .statusCode(response.statusCode())
                    .headers(response.headers().map())
                    .body(body)
                    .build();
        } else {
            throw new HttpException(response.statusCode(), body);
        }
    }

    @Override
    public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
        GovernedTransportService transport = resolveTransport();
        String actorUserId = WorkspaceContext.getUserIdOrUnknown();
        AtomicBoolean closed = new AtomicBoolean(false);
        Runnable safeClose = () -> {
            if (closed.compareAndSet(false, true)) {
                listener.onClose();
            }
        };

        try {
            java.net.http.HttpRequest httpRequest = buildHttpRequest(request);
            HttpResponse<InputStream> response = transport.send(
                    request.url(),
                    OutboundPurpose.PROVIDER_API_CALL,
                    null,
                    actorUserId,
                    true,
                    httpRequest,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String errorBody;
                try (InputStream is = response.body()) {
                    errorBody = is != null ? new String(is.readAllBytes(), StandardCharsets.UTF_8) : "";
                }
                HttpException ex = new HttpException(response.statusCode(), errorBody);
                listener.onError(ex);
                safeClose.run();
                return;
            }

            SuccessfulHttpResponse success = SuccessfulHttpResponse.builder()
                    .statusCode(response.statusCode())
                    .headers(response.headers().map())
                    .body((String) null)
                    .build();
            listener.onOpen(success);

            Duration streamTimeout = readTimeout != null
                    ? readTimeout
                    : (connectTimeout != null ? connectTimeout : Duration.ofSeconds(30));
            try (InputStream rawIs = response.body()) {
                if (rawIs != null) {
                    try (WatchdogInputStream is = new WatchdogInputStream(rawIs, streamTimeout)) {
                        parser.parse(is, listener);
                    }
                }
            }
            safeClose.run();
        } catch (Exception e) {
            listener.onError(e);
            safeClose.run();
        }
    }

    private GovernedTransportService resolveTransport() {
        if (governedTransport != null) {
            return governedTransport;
        }
        GovernedTransportService fallback = GovernedLangChainHttpClientBuilderFactory.getDefaultTransport();
        if (fallback != null) {
            return fallback;
        }
        try {
            ArcContainer container = Arc.container();
            if (container != null) {
                InstanceHandle<GovernedTransportService> handle = container.instance(GovernedTransportService.class);
                if (handle.isAvailable()) {
                    return handle.get();
                }
            }
        } catch (NoClassDefFoundError _) {
            // CDI not active in standalone test environment
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to resolve GovernedTransportService from CDI container: " + LogUtil.rootMessage(e), e);
        }
        throw new IllegalStateException(
                "Governed outbound transport (GovernedTransportService) is mandatory for LangChain4j provider calls: no instance injected or discoverable");
    }

    static class WatchdogInputStream extends FilterInputStream {
        private final Duration timeout;
        private final AtomicBoolean timedOut = new AtomicBoolean(false);
        private volatile ScheduledFuture<?> future;

        WatchdogInputStream(InputStream in, Duration timeout) {
            super(in);
            this.timeout = timeout;
            armWatchdog();
        }

        private void armWatchdog() {
            ScheduledFuture<?> old = future;
            if (old != null) {
                old.cancel(false);
            }
            if (!timedOut.get() && timeout != null && !timeout.isZero() && !timeout.isNegative()) {
                future = SSE_WATCHDOG_EXECUTOR.schedule(this::onTimeout, timeout.toMillis(), TimeUnit.MILLISECONDS);
            }
        }

        private void onTimeout() {
            timedOut.set(true);
            try {
                in.close();
            } catch (IOException _) {
                // Ignore close error on timeout
            }
        }

        @Override
        public int read() throws IOException {
            armWatchdog();
            try {
                int b = super.read();
                armWatchdog();
                return b;
            } catch (IOException e) {
                if (timedOut.get()) {
                    throw new HttpTimeoutException("SSE stream read timed out after " + timeout);
                }
                throw e;
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            armWatchdog();
            try {
                int n = super.read(b, off, len);
                armWatchdog();
                return n;
            } catch (IOException e) {
                if (timedOut.get()) {
                    throw new HttpTimeoutException("SSE stream read timed out after " + timeout);
                }
                throw e;
            }
        }

        @Override
        public void close() throws IOException {
            ScheduledFuture<?> old = future;
            if (old != null) {
                old.cancel(false);
            }
            super.close();
        }
    }

    private java.net.http.HttpRequest buildHttpRequest(HttpRequest request) {
        URI uri = URI.create(request.url());
        java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder(uri);
        Duration timeout = readTimeout != null ? readTimeout : connectTimeout;
        if (timeout != null) {
            builder.timeout(timeout);
        }

        if (request.headers() != null) {
            for (Map.Entry<String, List<String>> entry : request.headers().entrySet()) {
                String headerName = entry.getKey();
                if (isRestrictedHeader(headerName)) {
                    continue;
                }
                List<String> values = entry.getValue();
                if (values != null) {
                    for (String val : values) {
                        if (val != null) {
                            builder.header(headerName, val);
                        }
                    }
                }
            }
        }

        if (request.method() == HttpMethod.POST) {
            String body = request.body();
            BodyPublisher publisher = (body != null && !body.isEmpty())
                    ? BodyPublishers.ofString(body, StandardCharsets.UTF_8)
                    : BodyPublishers.noBody();
            builder.POST(publisher);
        } else if (request.method() == HttpMethod.GET) {
            builder.GET();
        } else if (request.method() == HttpMethod.DELETE) {
            builder.DELETE();
        } else {
            throw new IllegalArgumentException("Unsupported HTTP method: " + request.method());
        }

        return builder.build();
    }

    private static boolean isRestrictedHeader(String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        String lower = name.trim().toLowerCase(Locale.ROOT);
        return "content-length".equals(lower)
                || "host".equals(lower)
                || "connection".equals(lower)
                || "upgrade".equals(lower)
                || "expect".equals(lower);
    }
}
