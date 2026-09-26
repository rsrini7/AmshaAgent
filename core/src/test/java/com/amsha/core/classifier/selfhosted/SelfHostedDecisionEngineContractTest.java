package com.amsha.core.classifier.selfhosted;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.classifier.api.Choice;
import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;
import com.amsha.classifier.api.DecisionRequest;
import com.amsha.classifier.api.DecisionResult;
import com.amsha.classifier.api.DecisionTransactionGuard;
import com.amsha.classifier.api.Noul;
import com.amsha.classifier.api.ProviderPlacementValidator;
import com.amsha.classifier.api.QuestionKey;
import com.amsha.classifier.api.RedactionPolicy;
import com.amsha.classifier.api.Score;
import com.amsha.classifier.api.StateProjectionPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SelfHostedDecisionEngineContractTest {

    private HttpServer server;
    private int port;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final AtomicReference<String> lastReceivedRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastReceivedAuthHeader = new AtomicReference<>();
    private volatile int responseStatus = 200;
    private volatile String responseBody = "{}";
    private volatile long responseDelayMs = 0;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/v1/evaluate", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                byte[] requestBytes = exchange.getRequestBody().readAllBytes();
                lastReceivedRequestBody.set(new String(requestBytes, StandardCharsets.UTF_8));
                lastReceivedAuthHeader.set(exchange.getRequestHeaders().getFirst("X-Amsha-Loopback-Token"));

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
        responseBody = """
                {
                  "answers": {
                    "risk": {
                      "choice": "LOW_RISK",
                      "confidence": 0.94,
                      "probabilities": { "LOW_RISK": 0.94, "BLOCK": 0.06 }
                    }
                  },
                  "ruleId": "laya-rule-01",
                  "latencyMs": 18.5
                }
                """;

        var config = SelfHostedProviderConfig.builder()
                .enabled(true)
                .endpoint("http://127.0.0.1:" + port + "/v1/evaluate")
                .timeout(Duration.ofSeconds(2))
                .defaultProjectionPolicy(StateProjectionPolicy.of(Set.of("tool_name", "risk"), RedactionPolicy.DEFAULT))
                .build();

        try (var engine = new SelfHostedDecisionEngine(config, httpClient, mapper)) {
            var reqBuilder = DecisionRequest.builder("dec.tool.risk", 1, "{\"tool_name\":\"execute_shell\"}");
            QuestionKey<Choice> riskKey =
                    reqBuilder.registerChoice("risk", "Assess risk", List.of("LOW_RISK", "BLOCK"));
            DecisionRequest request = reqBuilder.build();

            DecisionResult result = engine.evaluate(request);
            assertInstanceOf(DecisionResult.Opinion.class, result);
            var opinion = (DecisionResult.Opinion) result;
            Choice choice = opinion.get(riskKey);
            assertEquals("LOW_RISK", choice.label());
            assertTrue(choice.confidence().isPresent());
            assertEquals(0.94, choice.confidence().getAsDouble(), 1e-4);
            assertEquals("laya-rule-01", opinion.ruleId());
        }
    }

    @Test
    void verifiesScoreWireCompatibility() {
        responseStatus = 200;
        responseBody = """
                {
                  "answers": {
                    "quality": {
                      "score": 2.5,
                      "confidence": 0.89,
                      "legend": ["poor", "fair", "good", "excellent"]
                    }
                  },
                  "ruleId": "kev-score-01"
                }
                """;

        var config = SelfHostedProviderConfig.builder()
                .enabled(true)
                .endpoint("http://127.0.0.1:" + port + "/v1/evaluate")
                .timeout(Duration.ofSeconds(2))
                .defaultProjectionPolicy(StateProjectionPolicy.of(Set.of("answer", "quality"), RedactionPolicy.DEFAULT))
                .build();

        try (var engine = new SelfHostedDecisionEngine(config, httpClient, mapper)) {
            var reqBuilder = DecisionRequest.builder("dec.quality", 1, "{\"answer\":\"good response\"}");
            QuestionKey<Score> scoreKey =
                    reqBuilder.registerScore("quality", "Rate response", List.of("poor", "fair", "good", "excellent"));
            DecisionRequest request = reqBuilder.build();

            DecisionResult result = engine.evaluate(request);
            assertInstanceOf(DecisionResult.Opinion.class, result);
            var opinion = (DecisionResult.Opinion) result;
            Score score = opinion.get(scoreKey);
            assertEquals(2.5, score.score(), 1e-4);
            assertEquals("kev-score-01", opinion.ruleId());
        }
    }

    @Test
    void verifiesNoulWireCompatibility() {
        responseStatus = 200;
        responseBody = """
                {
                  "answers": {
                    "is_safe": {
                      "probability": 0.96
                    }
                  }
                }
                """;

        var config = SelfHostedProviderConfig.builder()
                .enabled(true)
                .endpoint("http://127.0.0.1:" + port + "/v1/evaluate")
                .timeout(Duration.ofSeconds(2))
                .defaultProjectionPolicy(
                        StateProjectionPolicy.of(Set.of("content", "is_safe"), RedactionPolicy.DEFAULT))
                .build();

        try (var engine = new SelfHostedDecisionEngine(config, httpClient, mapper)) {
            var reqBuilder = DecisionRequest.builder("dec.safety", 1, "{\"content\":\"safe prompt\"}");
            QuestionKey<Noul> noulKey = reqBuilder.registerNoul("is_safe", "Is content safe");
            DecisionRequest request = reqBuilder.build();

            DecisionResult result = engine.evaluate(request);
            assertInstanceOf(DecisionResult.Opinion.class, result);
            var opinion = (DecisionResult.Opinion) result;
            Noul noul = opinion.get(noulKey);
            assertEquals(0.96, noul.probability(), 1e-4);
        }
    }

    @Test
    void verifiesMalformedAndPartialResponsesFailClosed() {
        var config = SelfHostedProviderConfig.builder()
                .enabled(true)
                .endpoint("http://127.0.0.1:" + port + "/v1/evaluate")
                .timeout(Duration.ofSeconds(2))
                .defaultProjectionPolicy(StateProjectionPolicy.of(Set.of("q1", "q2"), RedactionPolicy.DEFAULT))
                .build();

        try (var engine = new SelfHostedDecisionEngine(config, httpClient, mapper)) {
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

            // 2. Malformed JSON -> INFERENCE_ERROR with fixed, payload-free diagnostic
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
    void verifiesOverloadTimeoutAndProcessFailure() {
        var config = SelfHostedProviderConfig.builder()
                .enabled(true)
                .endpoint("http://127.0.0.1:" + port + "/v1/evaluate")
                .timeout(Duration.ofMillis(100))
                .defaultProjectionPolicy(StateProjectionPolicy.of(Set.of("q"), RedactionPolicy.DEFAULT))
                .build();

        try (var engine = new SelfHostedDecisionEngine(config, httpClient, mapper)) {
            var reqBuilder = DecisionRequest.builder("dec.status", 1, "{}").timeout(Duration.ofMillis(100));
            reqBuilder.registerChoice("q", "question", List.of("A", "B"));
            DecisionRequest request = reqBuilder.build();

            // 1. 503 Overloaded -> OVERLOAD_REJECTED
            responseStatus = 503;
            responseBody = "service unavailable";
            DecisionResult res503 = engine.evaluate(request);
            assertInstanceOf(DecisionResult.NoOpinion.class, res503);
            assertEquals(NoOpinionReason.OVERLOAD_REJECTED, ((DecisionResult.NoOpinion) res503).reason());

            // 2. Timeout -> TIMEOUT
            responseStatus = 200;
            responseDelayMs = 250;
            DecisionResult timeoutRes = engine.evaluate(request);
            assertInstanceOf(DecisionResult.NoOpinion.class, timeoutRes);
            assertEquals(NoOpinionReason.TIMEOUT, ((DecisionResult.NoOpinion) timeoutRes).reason());
            responseDelayMs = 0;

            // 3. Process failure / server stopped -> MISSING_MODEL
            server.stop(0);
            server = null;
            DecisionResult connRefusedRes = engine.evaluate(request);
            assertInstanceOf(DecisionResult.NoOpinion.class, connRefusedRes);
            assertEquals(NoOpinionReason.MISSING_MODEL, ((DecisionResult.NoOpinion) connRefusedRes).reason());
        }
    }

    @Test
    void verifiesDisabledProviderReturnsNoOpinionDisabled() {
        var config = SelfHostedProviderConfig.builder()
                .enabled(false) // explicitly disabled
                .endpoint("http://127.0.0.1:" + port + "/v1/evaluate")
                .build();

        try (var engine = new SelfHostedDecisionEngine(config, httpClient, mapper)) {
            var reqBuilder = DecisionRequest.builder("dec.disabled", 1, "{}");
            reqBuilder.registerChoice("q", "prompt", List.of("A", "B"));
            DecisionResult result = engine.evaluate(reqBuilder.build());

            assertInstanceOf(DecisionResult.NoOpinion.class, result);
            assertEquals(NoOpinionReason.DISABLED, ((DecisionResult.NoOpinion) result).reason());
        }
    }

    @Test
    void contractTestProvesExcludedFieldsAndSecretsAreAbsentFromOutboundPayload() {
        // Acceptance criterion: Contract tests prove excluded fields are absent from requests, logs, and evidence
        responseStatus = 200;
        responseBody = "{\"answers\":{\"tool_risk\":\"LOW_RISK\"}}";

        String testApiKey = "sk-proj-" + "supersecret1234567890";
        String rawState = """
                {
                  "tool_name": "execute_shell",
                  "agent_role": "coder",
                  "goal": "Review code for user@example.com",
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

        var config = SelfHostedProviderConfig.builder()
                .enabled(true)
                .endpoint("http://127.0.0.1:" + port + "/v1/evaluate")
                .timeout(Duration.ofSeconds(2))
                .loopbackToken("loopback-auth-secret")
                .defaultProjectionPolicy(projectionPolicy)
                .build();

        try (var engine = new SelfHostedDecisionEngine(config, httpClient, mapper)) {
            var reqBuilder = DecisionRequest.builder("dec.leak.check", 1, rawState);
            reqBuilder.registerChoice("tool_risk", "prompt", List.of("LOW_RISK", "BLOCK"));
            engine.evaluate(reqBuilder.build());

            String receivedBody = lastReceivedRequestBody.get();
            assertNotNull(receivedBody, "Server must have received HTTP request body");

            // Excluded fields must be completely absent from the wire body
            assertFalse(receivedBody.contains("unapproved_internal_debug"), receivedBody);
            assertFalse(receivedBody.contains("critical-leak-value"), receivedBody);
            assertFalse(receivedBody.contains("session_auth_token"), receivedBody);
            assertFalse(receivedBody.contains("secret-session-token-999"), receivedBody);

            // Sensitive secrets must be redacted
            assertFalse(receivedBody.contains(testApiKey), receivedBody);
            assertFalse(receivedBody.contains("my_hidden_password"), receivedBody);

            // PII must be redacted
            assertFalse(receivedBody.contains("user@example.com"), receivedBody);

            // Allowed fields and redaction markers must be present
            assertTrue(receivedBody.contains("execute_shell"), receivedBody);
            assertTrue(receivedBody.contains("coder"), receivedBody);
            assertTrue(receivedBody.contains("[REDACTED]"), receivedBody);

            // Loopback token header must be present
            assertEquals("loopback-auth-secret", lastReceivedAuthHeader.get());
        }
    }

    @Test
    void verifiesMissingProjectionPolicyFailsClosedWithoutInventingAllowList() {
        // When no default projection policy is set on config, and request has no explicit policy,
        // the engine must fail closed rather than deriving allow-list from question IDs.
        var config = SelfHostedProviderConfig.builder()
                .enabled(true)
                .endpoint("http://127.0.0.1:" + port + "/v1/evaluate")
                .timeout(Duration.ofSeconds(2))
                .defaultProjectionPolicy(null)
                .build();

        try (var engine = new SelfHostedDecisionEngine(config, httpClient, mapper)) {
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

        var config = SelfHostedProviderConfig.builder()
                .enabled(true)
                .endpoint("http://127.0.0.1:" + port + "/v1/evaluate")
                .timeout(Duration.ofSeconds(2))
                .defaultProjectionPolicy(StateProjectionPolicy.of(Set.of("param"), RedactionPolicy.DEFAULT))
                .build();

        var engine = new SelfHostedDecisionEngine(config, httpClient, mapper);
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
    void verifiesNestedDotDelimitedFieldPathMaskingInContractPayload() {
        responseStatus = 200;
        responseBody = "{\"answers\":{\"tool_risk\":\"LOW_RISK\"}}";

        String rawState = """
                {
                  "tool_name": "execute_shell",
                  "parameters": {
                    "command": "git push --force",
                    "safe_dir": "/tmp/work"
                  }
                }
                """;

        Set<String> allowList = Set.of("tool_name", "parameters");
        // Explicitly mask nested field: parameters.command
        RedactionPolicy redactionPolicy = new RedactionPolicy(false, false, Set.of("parameters.command"));
        StateProjectionPolicy projectionPolicy = StateProjectionPolicy.of(allowList, redactionPolicy);

        var config = SelfHostedProviderConfig.builder()
                .enabled(true)
                .endpoint("http://127.0.0.1:" + port + "/v1/evaluate")
                .timeout(Duration.ofSeconds(2))
                .defaultProjectionPolicy(projectionPolicy)
                .build();

        try (var engine = new SelfHostedDecisionEngine(config, httpClient, mapper)) {
            var reqBuilder = DecisionRequest.builder("dec.mask.path", 1, rawState);
            reqBuilder.registerChoice("tool_risk", "prompt", List.of("LOW_RISK", "BLOCK"));
            engine.evaluate(reqBuilder.build());

            String receivedBody = lastReceivedRequestBody.get();
            assertNotNull(receivedBody);
            assertTrue(receivedBody.contains("execute_shell"));
            assertTrue(receivedBody.contains("safe_dir"));
            assertFalse(receivedBody.contains("git push --force"));
            assertTrue(receivedBody.contains("[REDACTED]"));
        }
    }

    @Test
    void verifiesProviderPlacementSecurityInvariant() {
        var config = SelfHostedProviderConfig.builder()
                .enabled(true)
                .endpoint("http://127.0.0.1:" + port + "/v1/evaluate")
                .build();

        try (var engine = new SelfHostedDecisionEngine(config, httpClient, mapper)) {
            // Placing SelfHostedDecisionEngine behind classifier SPIs must throw SecurityException
            SecurityException ex = assertThrows(
                    SecurityException.class, () -> ProviderPlacementValidator.validateClassifierSpiPlacement(engine));
            assertTrue(ex.getMessage().contains("SelfHostedDecisionProvider"), ex.getMessage());
        }
    }

    @Test
    void verifiesDefensiveTransactionGuardFailsClosed() {
        var config = SelfHostedProviderConfig.builder()
                .enabled(true)
                .endpoint("http://127.0.0.1:" + port + "/v1/evaluate")
                .build();

        // Transaction guard reporting active JTA transaction
        DecisionTransactionGuard activeTxGuard =
                DecisionTransactionGuard.ofStatusSupplier(() -> 0); // STATUS_ACTIVE = 0

        try (var engine = new SelfHostedDecisionEngine(config, httpClient, mapper, activeTxGuard)) {
            var reqBuilder = DecisionRequest.builder("dec.tx", 1, "{}");
            reqBuilder.registerChoice("q", "prompt", List.of("A", "B"));

            DecisionResult result = engine.evaluate(reqBuilder.build());
            assertInstanceOf(DecisionResult.NoOpinion.class, result);
            assertEquals(NoOpinionReason.OVERLOAD_REJECTED, ((DecisionResult.NoOpinion) result).reason());

            // Server must NOT have received any HTTP request
            assertEquals(null, lastReceivedRequestBody.get(), "No HTTP call may occur inside active transaction");
        }
    }
}
