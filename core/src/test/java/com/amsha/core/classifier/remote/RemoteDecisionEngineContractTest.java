package com.amsha.core.classifier.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.transaction.Status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.classifier.api.Choice;
import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;
import com.amsha.classifier.api.DecisionRequest;
import com.amsha.classifier.api.DecisionResult;
import com.amsha.classifier.api.DecisionTransactionGuard;
import com.amsha.classifier.api.Noul;
import com.amsha.classifier.api.ProviderCategory;
import com.amsha.classifier.api.ProviderPlacementValidator;
import com.amsha.classifier.api.QuestionKey;
import com.amsha.classifier.api.RedactionPolicy;
import com.amsha.classifier.api.Score;
import com.amsha.classifier.api.StateProjectionPolicy;
import com.amsha.core.audit.AuditService;
import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.security.api.OutboundPolicyDecision;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.vertx.core.Vertx;

class RemoteDecisionEngineContractTest {

    private static final String TEST_CA_DER_BASE64 =
            "MIIBIjCByAIJAJufsTusFMEsMAoGCCqGSM49BAMCMBgxFjAUBgNVBAMMDWFtc2hhLXRlc3QtY2EwIBcNMjYwOTE1MDYwODMzWhgPMjEyNjA4MjIwNjA4MzNaMBgxFjAUBgNVBAMMDWFtc2hhLXRlc3QtY2EwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAASgZPFms5FHypxeAQNpxtoz6SBpARICHC1QLegRjxgTGDHDFE8MFl/yVPdBlAyFE54kIlGzDCaoR3QOpMcTwNNAMAoGCCqGSM49BAMCA0kAMEYCIQDY5P+79NzWzI2bc4OMDNoBPJ/I13NWuxh5kMpM8C/KQwIhAJ1SEnGItOwOLJmsjacbQchm9i2z7xQUKqhVwNuIoODC";

    private HttpServer server;
    private int port;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final AuditService auditService = mock(AuditService.class);
    private OutboundHttpPolicyService outboundPolicyService;
    private final AtomicReference<String> lastReceivedRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastReceivedAuthHeader = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private volatile int responseStatus = 200;
    private volatile String responseBody = "{}";
    private volatile long responseDelayMs = 0;

    @BeforeEach
    void setUp() throws Exception {
        outboundPolicyService = new OutboundHttpPolicyService();
        var auditField = OutboundHttpPolicyService.class.getDeclaredField("auditService");
        auditField.setAccessible(true);
        auditField.set(outboundPolicyService, auditService);

        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            port = server.getAddress().getPort();
            server.createContext("/v1/systemone", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws IOException {
                    requestCount.incrementAndGet();
                    byte[] requestBytes = exchange.getRequestBody().readAllBytes();
                    lastReceivedRequestBody.set(new String(requestBytes, StandardCharsets.UTF_8));
                    lastReceivedAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));

                    if (responseDelayMs > 0) {
                        try {
                            Thread.sleep(responseDelayMs);
                        } catch (InterruptedException _) {
                            Thread.currentThread().interrupt();
                        }
                    }

                    byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(responseStatus, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                }
            });
            server.start();
        } catch (IOException _) {
            server = null;
        }
    }

    private void assumeServerAvailable() {
        assumeTrue(server != null, "Loopback server binding not permitted in this environment");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void verifiesChoiceWireCompatibility() {
        responseStatus = 200;
        // The documented answer shape, plus two Amsha envelope fields ("ruleId", "latencyMs") that the
        // documented TypeSafe response does not contain — kept deliberately so this case also exercises
        // the decoder's tolerance of extra envelope keys. Do not read it as a vendor-verbatim body.
        responseBody = """
                {
                  "model": "jev-1.13.0",
                  "answers": {
                    "risk": {
                      "type": "choice",
                      "choice": "LOW_RISK",
                      "confidence": 0.95,
                      "probabilities": { "LOW_RISK": 0.95, "BLOCK": 0.05 }
                    }
                  },
                  "ruleId": "remote-risk-rule-01",
                  "latencyMs": 42.0,
                  "usage": { "input_tokens": 318, "output_tokens": 34 }
                }
                """;

        var config = RemoteProviderConfig.builder()
                .enabled(true)
                .endpoint("http://127.0.0.1:" + port + "/v1/systemone")
                .modelId("jev-1.13.0")
                .timeout(Duration.ofSeconds(2))
                .defaultProjectionPolicy(StateProjectionPolicy.of(Set.of("tool_name", "risk"), RedactionPolicy.DEFAULT))
                .build();

        try (var engine = new RemoteDecisionEngine(config, httpClient, mapper, outboundPolicyService)) {
            assertEquals(ProviderCategory.REMOTE, engine.category());

            var reqBuilder = DecisionRequest.builder("dec.remote.risk", 1, "{\"tool_name\":\"execute_shell\"}");
            QuestionKey<Choice> riskKey =
                    reqBuilder.registerChoice("risk", "Assess risk", List.of("LOW_RISK", "BLOCK"));
            DecisionRequest request = reqBuilder.build();

            DecisionResult result = engine.evaluate(request);
            assertInstanceOf(DecisionResult.Opinion.class, result);
            var opinion = (DecisionResult.Opinion) result;
            Choice choice = opinion.get(riskKey);
            assertEquals("LOW_RISK", choice.label());
            assertTrue(choice.confidence().isPresent());
            assertEquals(0.95, choice.confidence().getAsDouble(), 1e-4);
            assertEquals("remote-risk-rule-01", opinion.ruleId());
            // The top-level "model" is attributed and the nested "usage" token counts are read.
            assertEquals("jev-1.13.0", opinion.metadata().modelId());
            assertEquals(318L, opinion.metadata().inputTokens());
            assertEquals(34L, opinion.metadata().outputTokens());
        }
    }

    @Test
    void verifiesScoreWireCompatibility() {
        responseStatus = 200;
        // Verbatim shape: Score "legend" and "probabilities" are objects keyed by level index, not arrays.
        responseBody = """
                {
                  "model": "jev-1.13.0",
                  "answers": {
                    "quality": {
                      "type": "score",
                      "score": 3.0,
                      "confidence": 0.91,
                      "legend": { "0": "poor", "1": "fair", "2": "good", "3": "excellent" },
                      "probabilities": { "0": 0.0, "1": 0.05, "2": 0.1, "3": 0.85 }
                    }
                  },
                  "ruleId": "remote-score-rule-02"
                }
                """;

        var config = RemoteProviderConfig.builder()
                .enabled(true)
                .endpoint("http://127.0.0.1:" + port + "/v1/systemone")
                .modelId("jev-1.13.0")
                .timeout(Duration.ofSeconds(2))
                .defaultProjectionPolicy(StateProjectionPolicy.of(Set.of("answer", "quality"), RedactionPolicy.DEFAULT))
                .build();

        try (var engine = new RemoteDecisionEngine(config, httpClient, mapper, outboundPolicyService)) {
            var reqBuilder = DecisionRequest.builder("dec.remote.quality", 1, "{\"answer\":\"great answer\"}");
            QuestionKey<Score> scoreKey =
                    reqBuilder.registerScore("quality", "Rate response", List.of("poor", "fair", "good", "excellent"));
            DecisionRequest request = reqBuilder.build();

            DecisionResult result = engine.evaluate(request);
            assertInstanceOf(DecisionResult.Opinion.class, result);
            var opinion = (DecisionResult.Opinion) result;
            Score score = opinion.get(scoreKey);
            assertEquals(3.0, score.score(), 1e-4);
            assertEquals("remote-score-rule-02", opinion.ruleId());
            // Index-keyed objects are normalized into positional lists in level order.
            assertEquals(List.of("poor", "fair", "good", "excellent"), score.legend());
            assertEquals(4, score.probabilities().size());
            assertEquals(3, score.mostLikelyLevel().orElse(-1));
        }
    }

    @Test
    void verifiesNoulWireCompatibility() {
        responseStatus = 200;
        // Verbatim shape: the Noul answer carries a top-level "noul" key.
        responseBody = """
                {
                  "model": "jev-1.13.0",
                  "answers": {
                    "is_safe": {
                      "type": "noul",
                      "noul": 0.98
                    }
                  }
                }
                """;

        var config = RemoteProviderConfig.builder()
                .enabled(true)
                .endpoint("http://127.0.0.1:" + port + "/v1/systemone")
                .modelId("jev-1.13.0")
                .timeout(Duration.ofSeconds(2))
                .defaultProjectionPolicy(
                        StateProjectionPolicy.of(Set.of("content", "is_safe"), RedactionPolicy.DEFAULT))
                .build();

        try (var engine = new RemoteDecisionEngine(config, httpClient, mapper, outboundPolicyService)) {
            var reqBuilder = DecisionRequest.builder("dec.remote.safety", 1, "{\"content\":\"safe prompt\"}");
            QuestionKey<Noul> noulKey = reqBuilder.registerNoul("is_safe", "Is content safe");
            DecisionRequest request = reqBuilder.build();

            DecisionResult result = engine.evaluate(request);
            assertInstanceOf(DecisionResult.Opinion.class, result);
            var opinion = (DecisionResult.Opinion) result;
            Noul noul = opinion.get(noulKey);
            assertEquals(0.98, noul.probability(), 1e-4);
        }
    }

    @Test
    void verifiesMalformedAndPartialResponsesFailClosed() {
        var config = RemoteProviderConfig.builder()
                .enabled(true)
                .endpoint("http://127.0.0.1:" + port + "/v1/systemone")
                .modelId("jev-1.13.0")
                .timeout(Duration.ofSeconds(2))
                .defaultProjectionPolicy(StateProjectionPolicy.of(Set.of("q1", "q2"), RedactionPolicy.DEFAULT))
                .build();

        try (var engine = new RemoteDecisionEngine(config, httpClient, mapper, outboundPolicyService)) {
            var reqBuilder = DecisionRequest.builder("dec.multi", 1, "{}");
            reqBuilder.registerChoice("q1", "first", List.of("A", "B"));
            reqBuilder.registerChoice("q2", "second", List.of("X", "Y"));
            DecisionRequest request = reqBuilder.build();

            // 1. Partial response (q2 missing) -> INFERENCE_ERROR
            responseStatus = 200;
            responseBody = "{\"answers\":{\"q1\":\"A\"}}";
            DecisionResult partialResult = engine.evaluate(request);
            assertInstanceOf(DecisionResult.NoOpinion.class, partialResult);
            assertEquals(NoOpinionReason.INFERENCE_ERROR, ((DecisionResult.NoOpinion) partialResult).reason());

            // 2. Malformed JSON -> INFERENCE_ERROR with payload-free diagnostic
            responseBody = "not valid json";
            DecisionResult malformedResult = engine.evaluate(request);
            assertInstanceOf(DecisionResult.NoOpinion.class, malformedResult);
            assertEquals(NoOpinionReason.INFERENCE_ERROR, ((DecisionResult.NoOpinion) malformedResult).reason());
            assertEquals(
                    "Failed to parse provider response JSON", ((DecisionResult.NoOpinion) malformedResult).detail());

            // 3. HTTP 400 Bad Request -> INFERENCE_ERROR
            responseStatus = 400;
            responseBody = "{\"error\":\"bad request\"}";
            DecisionResult http400Result = engine.evaluate(request);
            assertInstanceOf(DecisionResult.NoOpinion.class, http400Result);
            assertEquals(NoOpinionReason.INFERENCE_ERROR, ((DecisionResult.NoOpinion) http400Result).reason());
        }
    }

    @Test
    void verifiesAuthFailures401And403() {
        var config = RemoteProviderConfig.builder()
                .enabled(true)
                .endpoint("http://127.0.0.1:" + port + "/v1/systemone")
                .modelId("jev-1.13.0")
                .timeout(Duration.ofSeconds(2))
                .defaultProjectionPolicy(StateProjectionPolicy.of(Set.of("q"), RedactionPolicy.DEFAULT))
                .build();

        try (var engine = new RemoteDecisionEngine(config, httpClient, mapper, outboundPolicyService)) {
            var reqBuilder = DecisionRequest.builder("dec.auth", 1, "{}");
            reqBuilder.registerChoice("q", "question", List.of("A", "B"));
            DecisionRequest request = reqBuilder.build();

            // 401 Unauthorized -> INFERENCE_ERROR with payload-free diagnostic
            responseStatus = 401;
            responseBody = "Unauthorized token";
            DecisionResult res401 = engine.evaluate(request);
            assertInstanceOf(DecisionResult.NoOpinion.class, res401);
            var noOp401 = (DecisionResult.NoOpinion) res401;
            assertEquals(NoOpinionReason.INFERENCE_ERROR, noOp401.reason());
            assertEquals("Remote provider authentication or authorization failed", noOp401.detail());
            assertFalse(noOp401.detail().contains("Unauthorized token"));

            // 403 Forbidden -> INFERENCE_ERROR
            responseStatus = 403;
            responseBody = "Forbidden scope";
            DecisionResult res403 = engine.evaluate(request);
            assertInstanceOf(DecisionResult.NoOpinion.class, res403);
            assertEquals(NoOpinionReason.INFERENCE_ERROR, ((DecisionResult.NoOpinion) res403).reason());
        }
    }

    @Test
    void verifiesModelNotFound404() {
        var config = RemoteProviderConfig.builder()
                .enabled(true)
                .endpoint("http://127.0.0.1:" + port + "/v1/systemone")
                .modelId("jev-1.13.0")
                .timeout(Duration.ofSeconds(2))
                .defaultProjectionPolicy(StateProjectionPolicy.of(Set.of("q"), RedactionPolicy.DEFAULT))
                .build();

        try (var engine = new RemoteDecisionEngine(config, httpClient, mapper, outboundPolicyService)) {
            var reqBuilder = DecisionRequest.builder("dec.404", 1, "{}");
            reqBuilder.registerChoice("q", "question", List.of("A", "B"));

            responseStatus = 404;
            responseBody = "Not found";
            DecisionResult res404 = engine.evaluate(reqBuilder.build());
            assertInstanceOf(DecisionResult.NoOpinion.class, res404);
            var noOp404 = (DecisionResult.NoOpinion) res404;
            assertEquals(NoOpinionReason.MISSING_MODEL, noOp404.reason());
            assertTrue(noOp404.detail().contains("404"));
        }
    }

    @Test
    void verifiesOverloadTimeoutAndProcessFailure() {
        var config = RemoteProviderConfig.builder()
                .enabled(true)
                .endpoint("http://127.0.0.1:" + port + "/v1/systemone")
                .modelId("jev-1.13.0")
                .timeout(Duration.ofMillis(100))
                .defaultProjectionPolicy(StateProjectionPolicy.of(Set.of("q"), RedactionPolicy.DEFAULT))
                .build();

        try (var engine = new RemoteDecisionEngine(config, httpClient, mapper, outboundPolicyService)) {
            var reqBuilder = DecisionRequest.builder("dec.status", 1, "{}").timeout(Duration.ofMillis(100));
            reqBuilder.registerChoice("q", "question", List.of("A", "B"));
            DecisionRequest request = reqBuilder.build();

            // 1. 503 Overloaded -> OVERLOAD_REJECTED
            responseStatus = 503;
            responseBody = "service unavailable";
            DecisionResult res503 = engine.evaluate(request);
            assertInstanceOf(DecisionResult.NoOpinion.class, res503);
            assertEquals(NoOpinionReason.OVERLOAD_REJECTED, ((DecisionResult.NoOpinion) res503).reason());

            // 2. 429 Rate limited -> OVERLOAD_REJECTED
            responseStatus = 429;
            responseBody = "too many requests";
            DecisionResult res429 = engine.evaluate(request);
            assertInstanceOf(DecisionResult.NoOpinion.class, res429);
            assertEquals(NoOpinionReason.OVERLOAD_REJECTED, ((DecisionResult.NoOpinion) res429).reason());

            // 3. 408 Status -> TIMEOUT
            responseStatus = 408;
            responseBody = "request timeout";
            DecisionResult res408 = engine.evaluate(request);
            assertInstanceOf(DecisionResult.NoOpinion.class, res408);
            assertEquals(NoOpinionReason.TIMEOUT, ((DecisionResult.NoOpinion) res408).reason());

            // 4. Delay / Client Timeout -> TIMEOUT
            responseStatus = 200;
            responseDelayMs = 250;
            DecisionResult timeoutRes = engine.evaluate(request);
            assertInstanceOf(DecisionResult.NoOpinion.class, timeoutRes);
            assertEquals(NoOpinionReason.TIMEOUT, ((DecisionResult.NoOpinion) timeoutRes).reason());
            responseDelayMs = 0;

            // 5. Process failure / server stopped -> MISSING_MODEL
            server.stop(0);
            server = null;
            DecisionResult connRefusedRes = engine.evaluate(request);
            assertInstanceOf(DecisionResult.NoOpinion.class, connRefusedRes);
            assertEquals(NoOpinionReason.MISSING_MODEL, ((DecisionResult.NoOpinion) connRefusedRes).reason());
        }
    }

    @Test
    void verifiesDisabledProviderReturnsNoOpinionDisabled() {
        var config = RemoteProviderConfig.builder()
                .enabled(false) // explicitly disabled by default
                .endpoint("http://127.0.0.1:" + port + "/v1/systemone")
                .modelId("jev-1.13.0")
                .build();

        try (var engine = new RemoteDecisionEngine(config, httpClient, mapper, outboundPolicyService)) {
            var reqBuilder = DecisionRequest.builder("dec.disabled", 1, "{}");
            reqBuilder.registerChoice("q", "prompt", List.of("A", "B"));
            DecisionResult result = engine.evaluate(reqBuilder.build());

            assertInstanceOf(DecisionResult.NoOpinion.class, result);
            assertEquals(NoOpinionReason.DISABLED, ((DecisionResult.NoOpinion) result).reason());
        }
    }

    @Test
    void contractTestProvesExcludedFieldsAndSecretsAreAbsentFromOutboundPayload() {
        responseStatus = 200;
        responseBody = "{\"answers\":{\"tool_risk\":\"LOW_RISK\"}}";

        String testApiKey = "remote-" + "secret-key-abcdef123456";
        String rawState = """
                {
                  "tool_name": "execute_shell",
                  "agent_role": "coder",
                  "goal": "Review code for user@corp.example.com",
                  "unapproved_internal_debug": "critical-leak-value",
                  "session_auth_token": "secret-session-token-999",
                  "parameters": {
                    "command": "git status",
                    "api_key": "%s",
                    "password": "my_hidden_password"
                  }
                }
                """.formatted(testApiKey);

        Set<String> allowList = Set.of("tool_name", "agent_role", "goal", "parameters");
        StateProjectionPolicy projectionPolicy =
                StateProjectionPolicy.of(allowList, new RedactionPolicy(true, true, Set.of()));

        var config = RemoteProviderConfig.builder()
                .enabled(true)
                .endpoint("http://127.0.0.1:" + port + "/v1/systemone")
                .modelId("jev-1.13.0")
                .timeout(Duration.ofSeconds(2))
                .apiKey("auth-" + "bearer-token-777")
                .defaultProjectionPolicy(projectionPolicy)
                .build();

        try (var engine = new RemoteDecisionEngine(config, httpClient, mapper, outboundPolicyService)) {
            var reqBuilder = DecisionRequest.builder("dec.leak.check", 1, rawState);
            reqBuilder.registerChoice("tool_risk", "prompt", List.of("LOW_RISK", "BLOCK"));
            engine.evaluate(reqBuilder.build());

            String receivedBody = lastReceivedRequestBody.get();
            assertNotNull(receivedBody, "Server must have received HTTP request body");

            // Excluded fields must be completely absent from wire body
            assertFalse(receivedBody.contains("unapproved_internal_debug"), receivedBody);
            assertFalse(receivedBody.contains("critical-leak-value"), receivedBody);
            assertFalse(receivedBody.contains("session_auth_token"), receivedBody);
            assertFalse(receivedBody.contains("secret-session-token-999"), receivedBody);

            // Sensitive secrets must be redacted
            assertFalse(receivedBody.contains(testApiKey), receivedBody);
            assertFalse(receivedBody.contains("my_hidden_password"), receivedBody);

            // PII must be redacted
            assertFalse(receivedBody.contains("user@corp.example.com"), receivedBody);

            // Allowed fields and redaction markers must be present
            assertTrue(receivedBody.contains("execute_shell"), receivedBody);
            assertTrue(receivedBody.contains("coder"), receivedBody);
            assertTrue(receivedBody.contains("[REDACTED]"), receivedBody);

            // Authorization header must be present
            assertEquals("Bearer " + "auth-" + "bearer-token-777", lastReceivedAuthHeader.get());
        }
    }

    @Test
    void verifiesMissingProjectionPolicyFailsClosedWithoutInventingAllowList() {
        var config = RemoteProviderConfig.builder()
                .enabled(true)
                .endpoint("http://127.0.0.1:" + port + "/v1/systemone")
                .modelId("jev-1.13.0")
                .timeout(Duration.ofSeconds(2))
                .defaultProjectionPolicy(null)
                .build();

        try (var engine = new RemoteDecisionEngine(config, httpClient, mapper, outboundPolicyService)) {
            var reqBuilder = DecisionRequest.builder("dec.no.policy", 1, "{\"tool_name\":\"execute_shell\"}");
            reqBuilder.registerChoice("tool_name", "prompt", List.of("execute_shell", "other"));
            DecisionResult result = engine.evaluate(reqBuilder.build());

            assertInstanceOf(DecisionResult.NoOpinion.class, result);
            assertEquals(NoOpinionReason.INFERENCE_ERROR, ((DecisionResult.NoOpinion) result).reason());
            assertTrue(((DecisionResult.NoOpinion) result)
                    .detail()
                    .contains("Missing allow-listed state projection policy"));
            assertEquals(null, lastReceivedRequestBody.get(), "No HTTP call should have been made");
        }
    }

    @Test
    void verifiesShutdownDrainsInFlightAndRejectsSubsequentEvaluations() throws Exception {
        responseStatus = 200;
        responseBody = "{\"answers\":{\"q\":\"A\"}}";
        responseDelayMs = 150;

        var config = RemoteProviderConfig.builder()
                .enabled(true)
                .endpoint("http://127.0.0.1:" + port + "/v1/systemone")
                .modelId("jev-1.13.0")
                .timeout(Duration.ofSeconds(2))
                .defaultProjectionPolicy(StateProjectionPolicy.of(Set.of("param"), RedactionPolicy.DEFAULT))
                .build();

        var engine = new RemoteDecisionEngine(config, httpClient, mapper, outboundPolicyService);
        var reqBuilder = DecisionRequest.builder("dec.drain", 1, "{\"param\":\"val\"}");
        reqBuilder.registerChoice("q", "prompt", List.of("A", "B"));
        DecisionRequest request = reqBuilder.build();

        var thread = new Thread(() -> engine.evaluate(request));
        thread.start();

        Thread.sleep(30);

        engine.close();

        thread.join(2000);
        assertFalse(thread.isAlive(), "In-flight evaluation thread should have completed during drain");
        assertEquals(0, engine.inFlightRequests(), "In-flight requests must be 0 after close");

        DecisionResult postCloseResult = engine.evaluate(request);
        assertInstanceOf(DecisionResult.NoOpinion.class, postCloseResult);
        assertEquals(NoOpinionReason.DISABLED, ((DecisionResult.NoOpinion) postCloseResult).reason());

        // Injected HttpClient lifecycle belongs to its creator and must remain unclosed
        assertFalse(httpClient.isTerminated(), "Shared HttpClient must remain unclosed after engine close");
    }

    @Test
    void verifiesProviderPlacementSecurityInvariant() {
        var config = RemoteProviderConfig.builder()
                .enabled(true)
                .endpoint("http://127.0.0.1:" + port + "/v1/systemone")
                .modelId("jev-1.13.0")
                .build();

        try (var engine = new RemoteDecisionEngine(config, httpClient, mapper, outboundPolicyService)) {
            // Placing RemoteDecisionEngine behind classifier SPIs must throw SecurityException
            SecurityException ex = assertThrows(
                    SecurityException.class, () -> ProviderPlacementValidator.validateClassifierSpiPlacement(engine));
            assertTrue(ex.getMessage().contains("RemoteDecisionProvider"), ex.getMessage());
        }
    }

    @Test
    void verifiesDefensiveTransactionGuardFailsClosed() {
        var config = RemoteProviderConfig.builder()
                .enabled(true)
                .endpoint("http://127.0.0.1:" + port + "/v1/systemone")
                .modelId("jev-1.13.0")
                .build();

        DecisionTransactionGuard activeTxGuard = DecisionTransactionGuard.ofStatusSupplier(() -> 0);

        try (var engine = new RemoteDecisionEngine(config, httpClient, mapper, activeTxGuard, outboundPolicyService)) {
            var reqBuilder = DecisionRequest.builder("dec.tx", 1, "{}");
            reqBuilder.registerChoice("q", "prompt", List.of("A", "B"));

            DecisionResult result = engine.evaluate(reqBuilder.build());
            assertInstanceOf(DecisionResult.NoOpinion.class, result);
            assertEquals(NoOpinionReason.OVERLOAD_REJECTED, ((DecisionResult.NoOpinion) result).reason());

            assertEquals(null, lastReceivedRequestBody.get(), "No HTTP call may occur inside active transaction");
        }
    }

    @Test
    void verifiesOutboundPolicyAndHitlSelfAuthorizationInvariant() {
        var config = RemoteProviderConfig.builder()
                .enabled(true)
                .endpoint("http://127.0.0.1:" + port + "/v1/systemone")
                .modelId("jev-1.13.0")
                .defaultProjectionPolicy(StateProjectionPolicy.of(Set.of("q"), RedactionPolicy.DEFAULT))
                .build();

        OutboundHttpPolicyService policyService = mock(OutboundHttpPolicyService.class);

        // 1. Outbound policy denies egress -> fail closed
        when(policyService.evaluate(anyString(), any(), any(), any()))
                .thenReturn(OutboundPolicyDecision.deny("EGRESS_BLOCKED", OutboundPurpose.PROVIDER_API_CALL, "system"));

        try (var engine = new RemoteDecisionEngine(
                config,
                httpClient,
                mapper,
                DecisionTransactionGuard.ofStatusSupplier(() -> Status.STATUS_NO_TRANSACTION),
                policyService)) {
            var reqBuilder = DecisionRequest.builder("dec.policy.deny", 1, "{}");
            reqBuilder.registerChoice("q", "prompt", List.of("A", "B"));

            DecisionResult result = engine.evaluate(reqBuilder.build());
            assertInstanceOf(DecisionResult.NoOpinion.class, result);
            var noOp = (DecisionResult.NoOpinion) result;
            assertEquals(NoOpinionReason.INFERENCE_ERROR, noOp.reason());
            assertTrue(noOp.detail().contains("Outbound policy denied egress"), noOp.detail());
            assertEquals(null, lastReceivedRequestBody.get(), "No HTTP call should be made when egress denied");
        }

        // 2. Outbound policy requires HITL -> remote evaluation cannot self-authorize
        when(policyService.evaluate(anyString(), any(), any(), any()))
                .thenReturn(OutboundPolicyDecision.requireHitl(
                        "Sensitive call", OutboundPurpose.PROVIDER_API_CALL, "system"));

        try (var engine = new RemoteDecisionEngine(
                config,
                httpClient,
                mapper,
                DecisionTransactionGuard.ofStatusSupplier(() -> Status.STATUS_NO_TRANSACTION),
                policyService)) {
            var reqBuilder = DecisionRequest.builder("dec.policy.hitl", 1, "{}");
            reqBuilder.registerChoice("q", "prompt", List.of("A", "B"));

            DecisionResult result = engine.evaluate(reqBuilder.build());
            assertInstanceOf(DecisionResult.NoOpinion.class, result);
            var noOp = (DecisionResult.NoOpinion) result;
            assertEquals(NoOpinionReason.OVERLOAD_REJECTED, noOp.reason());
            assertTrue(noOp.detail().contains("cannot self-authorize"), noOp.detail());
            assertEquals(null, lastReceivedRequestBody.get(), "No HTTP call should be made when HITL required");
        }
    }

    @Test
    void verifiesEventLoopThreadGuardFailsFast() throws Exception {
        var config = RemoteProviderConfig.builder()
                .enabled(true)
                .endpoint("http://127.0.0.1:" + port + "/v1/systemone")
                .modelId("jev-1.13.0")
                .defaultProjectionPolicy(StateProjectionPolicy.of(Set.of("q"), RedactionPolicy.DEFAULT))
                .build();

        try (var engine = new RemoteDecisionEngine(config, httpClient, mapper, outboundPolicyService)) {
            var reqBuilder = DecisionRequest.builder("dec.eventloop", 1, "{}");
            reqBuilder.registerChoice("q", "prompt", List.of("A", "B"));
            DecisionRequest request = reqBuilder.build();

            Vertx vertx = Vertx.vertx();
            try {
                CountDownLatch latch = new CountDownLatch(1);
                AtomicReference<DecisionResult> resultRef = new AtomicReference<>();
                vertx.runOnContext(v -> {
                    resultRef.set(engine.evaluate(request));
                    latch.countDown();
                });
                assertTrue(latch.await(5, TimeUnit.SECONDS));
                assertInstanceOf(DecisionResult.NoOpinion.class, resultRef.get());
                var noOpinion = (DecisionResult.NoOpinion) resultRef.get();
                assertEquals(NoOpinionReason.OVERLOAD_REJECTED, noOpinion.reason());
                assertTrue(noOpinion.detail().contains("Vert.x event-loop thread"), noOpinion.detail());
                assertEquals(null, lastReceivedRequestBody.get(), "No HTTP call should be made from event-loop thread");
            } finally {
                vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void verifiesProxyTransportPolicyEnforcement() {
        String netProfileWithProxy = """
                {
                  "egressMode": "ALLOW_ALL",
                  "requireProxy": true,
                  "proxyUrl": "http://127.0.0.1:8888"
                }
                """;
        var config = RemoteProviderConfig.builder()
                .enabled(true)
                .endpoint("http://127.0.0.1:" + port + "/v1/systemone")
                .modelId("jev-1.13.0")
                .networkProfileJson(netProfileWithProxy)
                .defaultProjectionPolicy(StateProjectionPolicy.of(Set.of("q"), RedactionPolicy.DEFAULT))
                .build();

        // 1. HttpClient without proxy -> fails closed to NoOpinion
        try (var engine = new RemoteDecisionEngine(config, httpClient, mapper, outboundPolicyService)) {
            var reqBuilder = DecisionRequest.builder("dec.proxy.missing", 1, "{}");
            reqBuilder.registerChoice("q", "prompt", List.of("A", "B"));

            DecisionResult result = engine.evaluate(reqBuilder.build());
            assertInstanceOf(DecisionResult.NoOpinion.class, result);
            var noOp = (DecisionResult.NoOpinion) result;
            assertEquals(NoOpinionReason.INFERENCE_ERROR, noOp.reason());
            assertTrue(noOp.detail().contains("Proxy transport required by connection policy"), noOp.detail());
            assertEquals(null, lastReceivedRequestBody.get(), "No HTTP call should be made without required proxy");
        }

        // 2. HttpClient with proxy configured -> succeeds
        HttpClient proxyClient = HttpClient.newBuilder()
                .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", port)))
                .build();
        try (var engine = new RemoteDecisionEngine(config, proxyClient, mapper, outboundPolicyService)) {
            responseStatus = 200;
            responseBody = "{\"answers\":{\"q\":\"A\"}}";

            var reqBuilder = DecisionRequest.builder("dec.proxy.ok", 1, "{}");
            reqBuilder.registerChoice("q", "prompt", List.of("A", "B"));

            DecisionResult result = engine.evaluate(reqBuilder.build());
            assertInstanceOf(DecisionResult.Opinion.class, result);
        }
    }

    @Test
    void verifiesConnectionEgressDenyAllPolicyEnforced() {
        String netProfileDenyAll = "{\"egressMode\": \"DENY_ALL\"}";
        var config = RemoteProviderConfig.builder()
                .enabled(true)
                .endpoint("http://127.0.0.1:" + port + "/v1/systemone")
                .modelId("jev-1.13.0")
                .networkProfileJson(netProfileDenyAll)
                .defaultProjectionPolicy(StateProjectionPolicy.of(Set.of("q"), RedactionPolicy.DEFAULT))
                .build();

        try (var engine = new RemoteDecisionEngine(config, httpClient, mapper, outboundPolicyService)) {
            var reqBuilder = DecisionRequest.builder("dec.egress.deny", 1, "{}");
            reqBuilder.registerChoice("q", "prompt", List.of("A", "B"));

            DecisionResult result = engine.evaluate(reqBuilder.build());
            assertInstanceOf(DecisionResult.NoOpinion.class, result);
            var noOp = (DecisionResult.NoOpinion) result;
            assertEquals(NoOpinionReason.INFERENCE_ERROR, noOp.reason());
            assertTrue(noOp.detail().contains("Outbound policy denied egress"), noOp.detail());
            assertEquals(
                    null,
                    lastReceivedRequestBody.get(),
                    "No HTTP call should be made when egress denied by connection policy");
        }
    }

    @Test
    void verifiesProxyCredentialsWithSecretVault() {
        assumeServerAvailable();
        String netProfileWithProxyCreds = """
                {
                  "egressMode": "ALLOW_ALL",
                  "requireProxy": true,
                  "proxyUrl": "http://127.0.0.1:8888",
                  "proxyCredentialVaultRef": "proxy-vault-ref"
                }
                """;
        var config = RemoteProviderConfig.builder()
                .enabled(true)
                .endpoint("http://127.0.0.1:" + port + "/v1/systemone")
                .modelId("jev-1.13.0")
                .networkProfileJson(netProfileWithProxyCreds)
                .defaultProjectionPolicy(StateProjectionPolicy.of(Set.of("q"), RedactionPolicy.DEFAULT))
                .build();

        // 1. Missing vault -> fails closed with INFERENCE_ERROR
        try (var engine = new RemoteDecisionEngine(
                config, mapper, DecisionTransactionGuard.system(), outboundPolicyService, null)) {
            var reqBuilder = DecisionRequest.builder("dec.proxy.novault", 1, "{}");
            reqBuilder.registerChoice("q", "prompt", List.of("A", "B"));

            DecisionResult result = engine.evaluate(reqBuilder.build());
            assertInstanceOf(DecisionResult.NoOpinion.class, result);
            var noOp = (DecisionResult.NoOpinion) result;
            assertEquals(NoOpinionReason.INFERENCE_ERROR, noOp.reason());
            assertTrue(
                    noOp.detail().contains("Proxy credentials are configured but no vault is available"),
                    noOp.detail());
        }

        // 2. Unresolved vault key -> fails closed with INFERENCE_ERROR
        SecretVault mockVault = mock(SecretVault.class);
        when(mockVault.get("proxy-vault-ref", "system")).thenReturn(Optional.empty());
        try (var engine = new RemoteDecisionEngine(
                config, mapper, DecisionTransactionGuard.system(), outboundPolicyService, mockVault)) {
            var reqBuilder = DecisionRequest.builder("dec.proxy.unresolved", 1, "{}");
            reqBuilder.registerChoice("q", "prompt", List.of("A", "B"));

            DecisionResult result = engine.evaluate(reqBuilder.build());
            assertInstanceOf(DecisionResult.NoOpinion.class, result);
            var noOp = (DecisionResult.NoOpinion) result;
            assertEquals(NoOpinionReason.INFERENCE_ERROR, noOp.reason());
            assertTrue(noOp.detail().contains("Proxy credential vault reference could not be resolved"), noOp.detail());
        }

        // 3. Valid credentials resolved from vault with proxy client -> succeeds
        VaultKey mockKey = mock(VaultKey.class);
        when(mockKey.get()).thenReturn("proxyuser:proxypass".getBytes(StandardCharsets.UTF_8));
        when(mockVault.get("proxy-vault-ref", "system")).thenReturn(Optional.of(mockKey));

        HttpClient proxyClient = HttpClient.newBuilder()
                .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", port)))
                .build();
        try (var engine = new RemoteDecisionEngine(
                config, mapper, DecisionTransactionGuard.system(), outboundPolicyService, mockVault, proxyClient)) {
            responseStatus = 200;
            responseBody = "{\"answers\":{\"q\":\"A\"}}";

            var reqBuilder = DecisionRequest.builder("dec.proxy.valid", 1, "{}");
            reqBuilder.registerChoice("q", "prompt", List.of("A", "B"));

            DecisionResult result = engine.evaluate(reqBuilder.build());
            assertInstanceOf(DecisionResult.Opinion.class, result);
        }
    }

    @Test
    void verifiesCustomCaTlsWithSecretVault() {
        assumeServerAvailable();
        String netProfileCustomCa = """
                {
                  "egressMode": "ALLOW_ALL",
                  "tlsTrustMode": "CUSTOM_CA",
                  "customCaVaultRef": "ca-vault-ref"
                }
                """;
        var config = RemoteProviderConfig.builder()
                .enabled(true)
                .endpoint("http://127.0.0.1:" + port + "/v1/systemone")
                .modelId("jev-1.13.0")
                .networkProfileJson(netProfileCustomCa)
                .defaultProjectionPolicy(StateProjectionPolicy.of(Set.of("q"), RedactionPolicy.DEFAULT))
                .build();

        // 1. Missing vault -> fails closed with INFERENCE_ERROR (TLS trust failure)
        try (var engine = new RemoteDecisionEngine(
                config, mapper, DecisionTransactionGuard.system(), outboundPolicyService, null)) {
            var reqBuilder = DecisionRequest.builder("dec.ca.novault", 1, "{}");
            reqBuilder.registerChoice("q", "prompt", List.of("A", "B"));

            DecisionResult result = engine.evaluate(reqBuilder.build());
            assertInstanceOf(DecisionResult.NoOpinion.class, result);
            var noOp = (DecisionResult.NoOpinion) result;
            assertEquals(NoOpinionReason.INFERENCE_ERROR, noOp.reason());
            assertTrue(noOp.detail().contains("Remote transport TLS trust establishment failed"), noOp.detail());
        }

        // 2. Unresolved CA vault reference -> fails closed
        SecretVault mockVault = mock(SecretVault.class);
        when(mockVault.get("ca-vault-ref", "system")).thenReturn(Optional.empty());
        try (var engine = new RemoteDecisionEngine(
                config, mapper, DecisionTransactionGuard.system(), outboundPolicyService, mockVault)) {
            var reqBuilder = DecisionRequest.builder("dec.ca.unresolved", 1, "{}");
            reqBuilder.registerChoice("q", "prompt", List.of("A", "B"));

            DecisionResult result = engine.evaluate(reqBuilder.build());
            assertInstanceOf(DecisionResult.NoOpinion.class, result);
            var noOp = (DecisionResult.NoOpinion) result;
            assertEquals(NoOpinionReason.INFERENCE_ERROR, noOp.reason());
            assertTrue(noOp.detail().contains("Remote transport TLS trust establishment failed"), noOp.detail());
        }

        // 3. Valid CA certificate in vault -> SSLContext built and succeeds without TLS trust exception
        VaultKey mockKey = mock(VaultKey.class);
        when(mockKey.get()).thenReturn(Base64.getDecoder().decode(TEST_CA_DER_BASE64));
        when(mockVault.get("ca-vault-ref", "system")).thenReturn(Optional.of(mockKey));

        try (var engine = new RemoteDecisionEngine(
                config, mapper, DecisionTransactionGuard.system(), outboundPolicyService, mockVault)) {
            responseStatus = 200;
            responseBody = "{\"answers\":{\"q\":\"A\"}}";

            var reqBuilder = DecisionRequest.builder("dec.ca.valid", 1, "{}");
            reqBuilder.registerChoice("q", "prompt", List.of("A", "B"));

            DecisionResult result = engine.evaluate(reqBuilder.build());
            assertInstanceOf(DecisionResult.Opinion.class, result);
        }
    }

    @Test
    void verifiesStrictlyNoRetriesOnTransientOrOverloadFailures() {
        assumeServerAvailable();
        var config = RemoteProviderConfig.builder()
                .enabled(true)
                .endpoint("http://127.0.0.1:" + port + "/v1/systemone")
                .modelId("jev-1.13.0")
                .timeout(Duration.ofSeconds(2))
                .defaultProjectionPolicy(StateProjectionPolicy.of(Set.of("q"), RedactionPolicy.DEFAULT))
                .build();

        try (var engine = new RemoteDecisionEngine(config, httpClient, mapper, outboundPolicyService)) {
            var reqBuilder = DecisionRequest.builder("dec.retry.test", 1, "{}");
            reqBuilder.registerChoice("q", "prompt", List.of("A", "B"));
            DecisionRequest request = reqBuilder.build();

            // 1. 503 Service Unavailable -> exactly 1 attempt (zero retries)
            responseStatus = 503;
            responseBody = "Service Unavailable";
            requestCount.set(0);

            DecisionResult res503 = engine.evaluate(request);
            assertInstanceOf(DecisionResult.NoOpinion.class, res503);
            assertEquals(NoOpinionReason.OVERLOAD_REJECTED, ((DecisionResult.NoOpinion) res503).reason());
            assertEquals(1, requestCount.get(), "503 must be single-shot: zero retries");

            // 2. 429 Too Many Requests -> exactly 1 attempt (zero retries)
            responseStatus = 429;
            responseBody = "Rate limited";
            requestCount.set(0);

            DecisionResult res429 = engine.evaluate(request);
            assertInstanceOf(DecisionResult.NoOpinion.class, res429);
            assertEquals(NoOpinionReason.OVERLOAD_REJECTED, ((DecisionResult.NoOpinion) res429).reason());
            assertEquals(1, requestCount.get(), "429 must be single-shot: zero retries");

            // 3. 500 Internal Server Error -> exactly 1 attempt (zero retries)
            responseStatus = 500;
            responseBody = "Internal error";
            requestCount.set(0);

            DecisionResult res500 = engine.evaluate(request);
            assertInstanceOf(DecisionResult.NoOpinion.class, res500);
            assertEquals(NoOpinionReason.INFERENCE_ERROR, ((DecisionResult.NoOpinion) res500).reason());
            assertEquals(1, requestCount.get(), "500 must be single-shot: zero retries");
        }
    }

    @Test
    void verifiesRequestMatchesTheDocumentedSystemOneContract() throws IOException {
        responseStatus = 200;
        responseBody = "{\"answers\":{\"department\":\"billing\"}}";

        var config = RemoteProviderConfig.builder()
                .enabled(true)
                .endpoint("http://127.0.0.1:" + port + "/v1/systemone")
                .modelId("jev-1.13.0")
                .timeout(Duration.ofSeconds(2))
                .defaultProjectionPolicy(StateProjectionPolicy.of(Set.of("ticket"), RedactionPolicy.DEFAULT))
                .build();

        try (var engine = new RemoteDecisionEngine(config, httpClient, mapper, outboundPolicyService)) {
            var reqBuilder = DecisionRequest.builder("dec.contract", 1, "{\"ticket\":\"charged twice\"}");
            reqBuilder.registerChoice("department", "Which team should handle this?", List.of("billing", "technical"));
            engine.evaluate(reqBuilder.build());

            String body = lastReceivedRequestBody.get();
            assertNotNull(body, "Server must have received HTTP request body");
            // Assert the emitted *structure*, not substrings: the defect this guards against was a
            // field in the wrong slot, or an extra field, neither of which a `contains` check sees.
            var sent = mapper.readTree(body);
            assertEquals(3, sent.size(), body);
            assertEquals("jev-1.13.0", sent.get("model").asText(), body);

            var projectedState = mapper.readTree(sent.get("state").asText());
            assertEquals(1, projectedState.size(), body);
            assertEquals("charged twice", projectedState.get("ticket").asText(), body);

            var questions = sent.get("questions");
            assertEquals(1, questions.size(), body);
            var question = questions.get("department");
            assertEquals(3, question.size(), body);
            assertEquals("choice", question.get("type").asText(), body);
            assertEquals(
                    "Which team should handle this?",
                    question.get("instructions").asText(),
                    body);

            var criteria = question.get("criteria");
            assertEquals(2, criteria.size(), body);
            assertTrue(criteria.get("billing").isNull(), body);
            assertTrue(criteria.get("technical").isNull(), body);

            // Amsha-internal fields are not part of the contract and must not be emitted at all.
            assertFalse(sent.has("decisionId"), body);
            assertFalse(sent.has("schemaVersion"), body);
            assertFalse(sent.has("timeoutMs"), body);
            assertFalse(sent.has("primitive"), body);
        }
    }

    @Test
    void verifies529OverloadIsMappedToOverloadRejected() {
        var config = RemoteProviderConfig.builder()
                .enabled(true)
                .endpoint("http://127.0.0.1:" + port + "/v1/systemone")
                .modelId("jev-1.13.0")
                .timeout(Duration.ofSeconds(2))
                .defaultProjectionPolicy(StateProjectionPolicy.of(Set.of("q"), RedactionPolicy.DEFAULT))
                .build();

        try (var engine = new RemoteDecisionEngine(config, httpClient, mapper, outboundPolicyService)) {
            var reqBuilder = DecisionRequest.builder("dec.529", 1, "{}");
            reqBuilder.registerChoice("q", "question", List.of("A", "B"));

            // 529 is the System One overload signal; there is no documented 503.
            responseStatus = 529;
            responseBody = "overloaded";
            DecisionResult result = engine.evaluate(reqBuilder.build());

            assertInstanceOf(DecisionResult.NoOpinion.class, result);
            assertEquals(NoOpinionReason.OVERLOAD_REJECTED, ((DecisionResult.NoOpinion) result).reason());
        }
    }

    @Test
    void verifiesUnrepresentableQuestionFailsClosedWithoutEgress() {
        var config = RemoteProviderConfig.builder()
                .enabled(true)
                .endpoint("http://127.0.0.1:" + port + "/v1/systemone")
                .modelId("jev-1.13.0")
                .timeout(Duration.ofSeconds(2))
                .defaultProjectionPolicy(StateProjectionPolicy.of(Set.of("q"), RedactionPolicy.DEFAULT))
                .build();

        try (var engine = new RemoteDecisionEngine(config, httpClient, mapper, outboundPolicyService)) {
            var reqBuilder = DecisionRequest.builder("dec.unrepresentable", 1, "{}");
            // A Score with no rubric levels has no valid System One representation.
            reqBuilder.registerScore("q", "rate", List.of());
            requestCount.set(0);

            DecisionResult result = engine.evaluate(reqBuilder.build());

            assertInstanceOf(DecisionResult.NoOpinion.class, result);
            var noOpinion = (DecisionResult.NoOpinion) result;
            assertEquals(NoOpinionReason.INFERENCE_ERROR, noOpinion.reason());
            assertTrue(noOpinion.detail().contains("q"), noOpinion.detail());
            assertEquals(0, requestCount.get(), "No HTTP call may be made for an unrepresentable request");
        }
    }
}
