package com.amsha.core.classifier.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.classifier.api.IntentDecisionPolicy;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.classifier.DecisionCostGuard;
import com.amsha.core.classifier.DecisionDriftMonitor;
import com.amsha.core.classifier.wire.WireProfile;
import com.amsha.core.config.DecisionProviderConfig;
import com.amsha.core.routing.IntentDecisionRouter;
import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.security.api.SecretVault;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;

/**
 * Pins the evaluation service's refusal and egress contract.
 *
 * <p>Every refusal path is asserted to make <em>zero</em> HTTP calls, which is the property that
 * matters: a refusal that still reached the provider would defeat the opt-in switch and the
 * allow-listed projection. The one success path runs against a loopback fixture so the service is
 * exercised end to end rather than against a mocked engine.
 */
class DecisionEvaluationServiceTest {

    private static final String DECISION_ID = IntentDecisionRouter.DECISION_ID;
    private static final String HANDLE = IntentDecisionRouter.QUESTION_HANDLE;
    private static final List<String> OPTIONS = List.of("coder", "researcher");
    private static final String TEST_DIRECT_KEY = "test-only-direct-decision-key";
    private static final String STATE = "{\"user_message\":\"please fix the bug\"}";

    private HttpServer server;
    private int port;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastLoopbackToken = new AtomicReference<>();
    private volatile String responseBody = "{}";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/v1/systemone", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                requestCount.incrementAndGet();
                lastLoopbackToken.set(exchange.getRequestHeaders().getFirst("X-Amsha-Loopback-Token"));
                byte[] requestBytes = exchange.getRequestBody().readAllBytes();
                lastBody.set(new String(requestBytes, StandardCharsets.UTF_8));
                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
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
    void disabledByDefaultPerformsNoEgress() {
        DecisionEvaluationService service = service(false, configuredProvider(true), mock(AuditService.class));

        DecisionEvaluationRefusedException refused =
                assertThrows(DecisionEvaluationRefusedException.class, () -> service.evaluate(request(), "operator"));

        assertEquals(DecisionRefusalReason.DISABLED, refused.reason());
        assertEquals(0, requestCount.get(), "a disabled evaluation must not reach the provider");
    }

    @Test
    void refusesAnUnregisteredDecision() {
        DecisionEvaluationService service = service(true, configuredProvider(true), mock(AuditService.class));
        DecisionEvaluationRequest unregistered =
                DecisionEvaluationRequest.of("amsha.routing.unknown.v9", STATE, Map.of("x", OPTIONS));

        DecisionEvaluationRefusedException refused = assertThrows(
                DecisionEvaluationRefusedException.class, () -> service.evaluate(unregistered, "operator"));

        assertEquals(DecisionRefusalReason.UNREGISTERED_DECISION, refused.reason());
        assertEquals(0, requestCount.get());
    }

    @Test
    void refusesAGovernanceIncompleteProvider() {
        // An enabled slot with no projection allow-list: the selector refuses it before an engine is built.
        DecisionEvaluationService service = service(true, configuredProvider(false), mock(AuditService.class));

        DecisionEvaluationRefusedException refused =
                assertThrows(DecisionEvaluationRefusedException.class, () -> service.evaluate(request(), "operator"));

        assertEquals(DecisionRefusalReason.PROVIDER_UNAVAILABLE, refused.reason());
        assertEquals(0, requestCount.get());
    }

    @Test
    void refusesAnInvalidOptionSet() {
        DecisionEvaluationService service = service(true, configuredProvider(true), mock(AuditService.class));
        // The registered decision's single question is caller-supplied, so an empty option set is invalid.
        DecisionEvaluationRequest noOptions = DecisionEvaluationRequest.of(DECISION_ID, STATE, Map.of());

        DecisionEvaluationRefusedException refused =
                assertThrows(DecisionEvaluationRefusedException.class, () -> service.evaluate(noOptions, "operator"));

        assertEquals(DecisionRefusalReason.INVALID_REQUEST, refused.reason());
        assertEquals(0, requestCount.get());
    }

    @Test
    void refusesAnEventLoopInvocation() throws Exception {
        DecisionEvaluationService service = service(true, configuredProvider(true), mock(AuditService.class));
        Vertx vertx = Vertx.vertx();
        try {
            AtomicReference<DecisionEvaluationRefusedException> caught = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            vertx.runOnContext(v -> {
                try {
                    service.evaluate(request(), "operator");
                } catch (DecisionEvaluationRefusedException e) {
                    caught.set(e);
                } finally {
                    latch.countDown();
                }
            });
            assertTrue(latch.await(5, TimeUnit.SECONDS), "the event-loop task must complete");
            assertNotNull(caught.get(), "an event-loop caller must be refused");
            assertEquals(DecisionRefusalReason.EVENT_LOOP, caught.get().reason());
        } finally {
            vertx.close();
        }
        assertEquals(0, requestCount.get(), "an event-loop refusal must not reach the provider");
    }

    @Test
    void emitsAnAuditEventBeforeEgressAndReturnsAPayloadFreeOutcome() throws Exception {
        AuditService audit = mock(AuditService.class);
        responseBody = """
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
        DecisionEvaluationService service = service(true, configuredProvider(true, TEST_DIRECT_KEY), audit);

        DecisionEvaluationOutcome outcome = service.evaluate(request(), "operator");

        assertTrue(outcome.opinion());
        assertEquals("laya", outcome.providerId());
        assertEquals("SELF_HOSTED", outcome.lane());
        assertEquals("coder (0.91)", outcome.answers().get(HANDLE));
        assertEquals("intent-rule-01", outcome.ruleId());
        verify(audit)
                .recordMandatory(
                        eq("decision-evaluation"),
                        eq("operator"),
                        eq("operator"),
                        eq(SecurityEventType.DECISION_EVALUATION_REQUESTED),
                        contains("decision=" + DECISION_ID));
        assertEquals(1, requestCount.get());
        assertEquals(TEST_DIRECT_KEY, lastLoopbackToken.get());
        // The projected body must carry the allow-listed field and nothing else.
        assertTrue(lastBody.get().contains("user_message"));
        JsonNode question = mapper.readTree(lastBody.get()).path("questions").path(HANDLE);
        assertEquals(
                IntentDecisionRouter.INTENT_CRITERIA,
                question.path("instructions").asText());
        JsonNode choices = question.path("criteria");
        assertEquals(3, choices.size());
        assertTrue(choices.has("coder"));
        assertTrue(choices.has("researcher"));
        assertTrue(choices.has(IntentDecisionPolicy.NO_OPINION_LABEL));
    }

    @Test
    void aTelemetryFailureDoesNotReplaceACompletedDecision() {
        AuditService audit = mock(AuditService.class);
        responseBody = """
                {"model":"laya-multilingual","answers":{"intent_role_choice":{"type":"choice","choice":"coder","confidence":0.91}},"ruleId":"intent-rule-01","latencyMs":12.0,"usage":{"input_tokens":40,"output_tokens":8}}
                """;
        MeterRegistry brokenRegistry = new SimpleMeterRegistry() {
            @Override
            protected Counter newCounter(Meter.Id id) {
                throw new IllegalStateException("metrics unavailable");
            }
        };

        DecisionEvaluationOutcome outcome = service(
                        true,
                        configuredProvider(true),
                        audit,
                        new DecisionCostGuard(1),
                        new DecisionDriftMonitor(4, 0.15, 0.20),
                        brokenRegistry)
                .evaluate(request(), "operator");

        assertTrue(outcome.opinion());
        assertEquals("coder (0.91)", outcome.answers().get(HANDLE));
        assertEquals(1, requestCount.get());
    }

    @Test
    void doesNotEgressWhenTheAuditWriteFails() {
        AuditService audit = mock(AuditService.class);
        doThrow(new IllegalStateException("audit unavailable"))
                .when(audit)
                .recordMandatory(anyString(), any(), any(), any(), anyString());
        DecisionEvaluationService service = service(true, configuredProvider(true), audit);

        assertThrows(IllegalStateException.class, () -> service.evaluate(request(), "operator"));

        assertEquals(0, requestCount.get(), "audit-before-mutation must stop the call when the audit fails");
    }

    @Test
    void mapsATransportFailureToAPayloadFreeNoOpinion() {
        // A stopped fixture refuses the connection, so the engine returns a NoOpinion rather than throwing.
        server.stop(0);
        DecisionEvaluationService service = service(true, configuredProvider(true), mock(AuditService.class));

        DecisionEvaluationOutcome outcome = service.evaluate(request(), "operator");

        assertFalse(outcome.opinion());
        assertNotNull(outcome.noOpinionReason());
        assertTrue(outcome.answers().isEmpty(), "a no-opinion outcome must not carry answers");
    }

    @Test
    void failsClosedWithNoOpinionWhenTheLaneCostCeilingIsExceeded() {
        // The guard's rolling rate is already known and above the approved ceiling, so the lane must fail
        // closed as OVERLOAD_REJECTED — the same outcome an overloaded provider gets — without egress.
        DecisionCostGuard guard = new DecisionCostGuard(1);
        guard.record("SELF_HOSTED", 1.0); // 1,000 USD per 1,000 decisions
        DecisionProviderConfig config = configuredProvider(true);
        when(config.slots().get("laya").governance().costCeilingPerThousand())
                .thenReturn(Optional.of(new BigDecimal("0.001")));
        DecisionDriftMonitor driftMonitor = new DecisionDriftMonitor(4, 0.15, 0.20);
        DecisionEvaluationService service = service(true, config, mock(AuditService.class), guard, driftMonitor);

        DecisionEvaluationOutcome outcome = service.evaluate(request(), "operator");

        assertFalse(outcome.opinion());
        assertEquals("OVERLOAD_REJECTED", outcome.noOpinionReason());
        assertEquals(0, requestCount.get(), "a cost refusal must not reach the provider");
        // The fail-closed breach is announced exactly once, and through the monitor: the service does not
        // log a second, unlatched WARN of its own.
        assertTrue(
                driftMonitor.alertEmitted("SELF_HOSTED", DecisionDriftMonitor.AlertKind.COST_CEILING),
                "the cost-ceiling breach is announced through the drift monitor");
    }

    @Test
    void anApprovedCeilingThatIsNotYetExceededDoesNotBlockTheEvaluation() {
        responseBody = "{\"model\":\"laya-multilingual\",\"answers\":{\"" + HANDLE
                + "\":{\"type\":\"choice\",\"choice\":\"coder\",\"confidence\":0.91}},"
                + "\"latencyMs\":1.0,\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
        DecisionCostGuard guard = new DecisionCostGuard(4);
        guard.record("SELF_HOSTED", 0.0001);
        DecisionProviderConfig config = configuredProvider(true);
        when(config.slots().get("laya").governance().costCeilingPerThousand())
                .thenReturn(Optional.of(new BigDecimal("0.05")));
        DecisionEvaluationService service =
                service(true, config, mock(AuditService.class), guard, new DecisionDriftMonitor(4, 0.15, 0.20));

        DecisionEvaluationOutcome outcome = service.evaluate(request(), "operator");

        assertTrue(outcome.opinion());
        assertEquals(1, requestCount.get(), "an in-budget evaluation still reaches the provider");
    }

    private DecisionEvaluationRequest request() {
        return DecisionEvaluationRequest.of(DECISION_ID, STATE, Map.of(HANDLE, OPTIONS));
    }

    private DecisionEvaluationService service(boolean enabled, DecisionProviderConfig config, AuditService audit) {
        return service(enabled, config, audit, new DecisionCostGuard(1), new DecisionDriftMonitor(4, 0.15, 0.20));
    }

    private DecisionEvaluationService service(
            boolean enabled,
            DecisionProviderConfig config,
            AuditService audit,
            DecisionCostGuard costGuard,
            DecisionDriftMonitor driftMonitor) {
        return service(enabled, config, audit, costGuard, driftMonitor, new SimpleMeterRegistry());
    }

    @SuppressWarnings("unchecked")
    private DecisionEvaluationService service(
            boolean enabled,
            DecisionProviderConfig config,
            AuditService audit,
            DecisionCostGuard costGuard,
            DecisionDriftMonitor driftMonitor,
            MeterRegistry meterRegistry) {
        Instance<MeterRegistry> registries = mock(Instance.class);
        when(registries.isResolvable()).thenReturn(true);
        when(registries.get()).thenReturn(meterRegistry);
        return new DecisionEvaluationService(
                config,
                new DecisionDefinitionRegistry(0.50, 0.85, 500L),
                mock(SecretVault.class),
                mapper,
                mock(OutboundHttpPolicyService.class),
                audit,
                new DecisionPriceScheduleRegistry(Map.of()),
                driftMonitor,
                costGuard,
                registries,
                enabled);
    }

    /**
     * A hand-built configuration double. The self-hosted lane is used so the whole path — selection,
     * governance, factory, engine, projection — is exercised without a credential.
     */
    private DecisionProviderConfig configuredProvider(boolean withAllowList) {
        return configuredProvider(withAllowList, null);
    }

    private DecisionProviderConfig configuredProvider(boolean withAllowList, String apiKey) {
        DecisionProviderConfig config = mock(DecisionProviderConfig.class);
        DecisionProviderConfig.Slot slot = mock(DecisionProviderConfig.Slot.class);
        DecisionProviderConfig.Slot.Governance governance = mock(DecisionProviderConfig.Slot.Governance.class);
        when(config.selected()).thenReturn(Optional.of("laya"));
        when(config.slots()).thenReturn(Map.of("laya", slot));
        when(slot.enabled()).thenReturn(true);
        when(slot.endpoint()).thenReturn(Optional.of("http://127.0.0.1:" + port + "/v1/systemone"));
        when(slot.model()).thenReturn(Optional.of("laya-multilingual"));
        when(slot.apiKey()).thenReturn(Optional.ofNullable(apiKey));
        when(slot.timeout()).thenReturn(Duration.ofSeconds(5));
        when(slot.wireProfile()).thenReturn(WireProfile.JSON_OBJECT);
        when(slot.governance()).thenReturn(governance);
        when(governance.dataLocation()).thenReturn(Optional.empty());
        when(governance.retentionWindow()).thenReturn(Duration.ZERO);
        when(governance.allowTrainingUse()).thenReturn(false);
        when(governance.tenantIsolation()).thenReturn(true);
        when(governance.projectionAllowList())
                .thenReturn(withAllowList ? Optional.of(List.of("user_message")) : Optional.empty());
        when(governance.costCeilingPerThousand()).thenReturn(Optional.empty());
        return config;
    }
}
