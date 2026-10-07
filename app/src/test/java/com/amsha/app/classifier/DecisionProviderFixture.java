package com.amsha.app.classifier;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/**
 * A loopback System One fixture for the decision evaluation path integration tests.
 *
 * <p>It binds the literal {@code 127.0.0.1} the self-hosted lane requires, on an ephemeral port, and
 * publishes the provider configuration the selector needs through the returned config overrides. It
 * also records the last request body, which is what lets the no-egress-leak test assert what actually
 * crossed the process boundary rather than what the code intended to send.
 *
 * <p>It deliberately does <em>not</em> set {@code amsha.decision.evaluation.enabled}: each test class
 * sets that through its own profile, so the same fixture serves both the enabled and disabled cases.
 */
public class DecisionProviderFixture implements QuarkusTestResourceLifecycleManager {

    /**
     * Verbatim System One response shape, keyed to the router's own question handle so the evaluation
     * path's registered decision is the one answered.
     */
    public static final String RESPONSE_BODY = """
            {
              "model": "laya-multilingual",
              "answers": {
                "intent_role_choice": {
                  "type": "choice",
                  "choice": "coder",
                  "confidence": 0.91,
                  "probabilities": { "coder": 0.91, "researcher": 0.09 }
                }
              },
              "ruleId": "intent-rule-01",
              "latencyMs": 12.0,
              "usage": { "input_tokens": 40, "output_tokens": 8 }
            }
            """;

    private static final AtomicInteger REQUEST_COUNT = new AtomicInteger();
    private static final AtomicReference<String> LAST_BODY = new AtomicReference<>();

    private HttpServer server;

    @Override
    public Map<String, String> start() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the decision provider fixture", e);
        }
        int port = server.getAddress().getPort();
        server.createContext("/v1/systemone", this::handle);
        server.start();
        return Map.ofEntries(
                Map.entry("amsha.decision.providers.selected", "laya"),
                Map.entry("amsha.decision.providers.slots.laya.enabled", "true"),
                Map.entry("amsha.decision.providers.slots.laya.endpoint", "http://127.0.0.1:" + port + "/v1/systemone"),
                Map.entry("amsha.decision.providers.slots.laya.model", "laya-multilingual"),
                Map.entry("amsha.decision.providers.slots.laya.governance.data-location", "SELF_HOSTED_LOCAL"),
                Map.entry("amsha.decision.providers.slots.laya.governance.projection-allow-list", "user_message"));
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        REQUEST_COUNT.incrementAndGet();
        LAST_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] bytes = RESPONSE_BODY.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** How many provider calls the fixture has served since the last {@link #reset()}. */
    public static int requestCount() {
        return REQUEST_COUNT.get();
    }

    /** The last request body the fixture received, or {@code null} when it has received none. */
    public static String lastBody() {
        return LAST_BODY.get();
    }

    /** Clears the recorded call count and body. */
    public static void reset() {
        REQUEST_COUNT.set(0);
        LAST_BODY.set(null);
    }
}
