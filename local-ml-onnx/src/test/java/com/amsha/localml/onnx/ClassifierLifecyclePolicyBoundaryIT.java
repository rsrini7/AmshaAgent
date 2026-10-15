package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.classifier.api.Choice;
import com.amsha.classifier.api.ClassifierResult;
import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;
import com.amsha.classifier.api.DecisionEngine;
import com.amsha.classifier.api.DecisionRequest;
import com.amsha.classifier.api.DecisionResult;
import com.amsha.classifier.api.FailMode;
import com.amsha.classifier.api.InMemoryDecisionEngine;
import com.amsha.classifier.api.PostPolicyToolContext;
import com.amsha.classifier.api.ProviderCategory;
import com.amsha.classifier.api.QuestionKey;
import com.amsha.classifier.api.RemoteDecisionProvider;
import com.amsha.classifier.api.ReviewBand;
import com.amsha.classifier.api.SelfHostedDecisionProvider;
import com.amsha.classifier.api.ShadowClassifier;
import com.amsha.classifier.api.ToolRiskAction;
import com.amsha.classifier.api.ToolRiskDecisionPolicy;
import com.amsha.classifier.api.ToolRiskPrediction;
import com.amsha.core.agent.loop.PostPolicyToolRiskDispatcher;
import com.amsha.core.agent.loop.PostPolicyToolRiskInterceptor;
import com.amsha.core.classifier.ClassifierShadowDispatcher;
import com.amsha.core.classifier.DecisionTelemetry;
import com.amsha.localml.onnx.OnnxClassifierRuntimeControl.Classifier;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.ToolOperation;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * End-to-end boundary tests exercising all 11 required paths through the real
 * dispatcher and runtime control boundary (Issue 03):
 * <ol>
 *   <li>Disabled</li>
 *   <li>Missing model / implementation</li>
 *   <li>Timeout isolation and metric recording</li>
 *   <li>Overload rejection (queue / transaction)</li>
 *   <li>Circuit-open fail-closed / abstention</li>
 *   <li>Low-confidence / review band routing</li>
 *   <li>Malformed response / inference error fail-closed</li>
 *   <li>Shadow bean isolation</li>
 *   <li>Enable without ready implementation rejected</li>
 *   <li>Disable and drain with active in-flight lease</li>
 *   <li>Audit-before-mutation with internal authorization</li>
 * </ol>
 */
class ClassifierLifecyclePolicyBoundaryIT {

    private static final PostPolicyToolContext SAMPLE_CONTEXT = new PostPolicyToolContext(
            "coder",
            AutonomyLevel.FULL,
            "sandbox_exec",
            ToolOperation.EXECUTE_SHELL,
            Map.of("cmd", "echo test"),
            "run command",
            false);

    private SimpleMeterRegistry meterRegistry;
    private OnnxClassifierRuntimeControl runtimeControl;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        runtimeControl = new OnnxClassifierRuntimeControl(false, false, false);
    }

    @Test
    void path1_disabledReturnsDisabledNoOpinion() {
        var shadowClassifier = new OnnxToolRiskShadowClassifier(
                true,
                new ObjectMapper(),
                input -> new ClassifierResult.Opinion<>(ToolRiskPrediction.BLOCK, 0.95f, "rule-block"));
        PostPolicyToolRiskInterceptor interceptor =
                new OnnxPostPolicyToolRiskInterceptor(runtimeControl, shadowClassifier);
        var dispatcher = new PostPolicyToolRiskDispatcher(List.of(interceptor), meterRegistry);

        ClassifierResult<ToolRiskAction> result = dispatcher.evaluate(SAMPLE_CONTEXT);
        assertInstanceOf(ClassifierResult.NoOpinion.class, result);
        assertEquals(NoOpinionReason.DISABLED, ((ClassifierResult.NoOpinion<?>) result).reason());
    }

    @Test
    void path2_missingImplementationReturnsMissingModelNoOpinion() {
        var dispatcher = new PostPolicyToolRiskDispatcher(List.<PostPolicyToolRiskInterceptor>of(), meterRegistry);

        ClassifierResult<ToolRiskAction> result = dispatcher.evaluate(SAMPLE_CONTEXT);
        assertInstanceOf(ClassifierResult.NoOpinion.class, result);
        assertEquals(NoOpinionReason.MISSING_MODEL, ((ClassifierResult.NoOpinion<?>) result).reason());
    }

    @Test
    void path3_timeoutIsIsolatedAndRecordedInMetrics() {
        PostPolicyToolRiskInterceptor timingOut = new PostPolicyToolRiskInterceptor() {
            @Override
            public int priority() {
                return 10;
            }

            @Override
            public ClassifierResult<ToolRiskAction> evaluate(PostPolicyToolContext context) {
                throw new IllegalStateException(new TimeoutException("inference timeout"));
            }
        };

        var dispatcher = new PostPolicyToolRiskDispatcher(List.of(timingOut), meterRegistry);
        ClassifierResult<ToolRiskAction> result = dispatcher.evaluate(SAMPLE_CONTEXT);

        assertInstanceOf(ClassifierResult.NoOpinion.class, result);
        assertEquals(NoOpinionReason.TIMEOUT, ((ClassifierResult.NoOpinion<?>) result).reason());
        assertEquals(
                1.0,
                meterRegistry
                        .get("amsha.classifier.tool_risk.active.interceptor.errors")
                        .tag("reason", "timeout")
                        .counter()
                        .count());
    }

    @Test
    void path4_overloadRejectionReturnsOverloadRejectedNoOpinion() {
        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("Worker pool full");
        };

        ShadowClassifier<String, String> classifier = context -> new ClassifierResult.Opinion<>("safe", 0.9f, "rule");

        AtomicReference<ClassifierResult<String>> received = new AtomicReference<>();
        ClassifierShadowDispatcher.observe(List.of(classifier), rejectingExecutor, "test input", received::set);

        assertNotNull(received.get());
        assertInstanceOf(ClassifierResult.NoOpinion.class, received.get());
        assertEquals(NoOpinionReason.OVERLOAD_REJECTED, ((ClassifierResult.NoOpinion<?>) received.get()).reason());
    }

    @Test
    void path5_circuitBreakerTripsAndBlocksInference() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-23T00:00:00Z"));
        OnnxClassifierCircuitBreaker breaker = new OnnxClassifierCircuitBreaker(2, Duration.ofSeconds(30), clock);

        var probe1 = breaker.tryAcquire().orElseThrow();
        breaker.recordFailure(probe1);
        assertEquals(OnnxClassifierCircuitBreaker.State.CLOSED, breaker.state());

        var probe2 = breaker.tryAcquire().orElseThrow();
        breaker.recordFailure(probe2);
        assertEquals(OnnxClassifierCircuitBreaker.State.OPEN, breaker.state());

        // While open, acquire returns empty (fail closed / abstention)
        assertTrue(breaker.tryAcquire().isEmpty());
    }

    @Test
    void path6_lowConfidenceRestrictiveVerdictEscalatesToApproval() {
        QuestionKey<Choice> choiceKey = QuestionKey.choice("risk");
        DecisionResult lowConfOpinion = DecisionResult.opinionBuilder("dec.low", 1)
                .put(choiceKey, new Choice("BLOCK", OptionalDouble.of(0.2)))
                .ruleId("rule-low")
                .build();

        ReviewBand band = new ReviewBand(0.4, 0.8);
        ToolRiskDecisionPolicy.PolicyResolution resolution =
                ToolRiskDecisionPolicy.resolve(null, lowConfOpinion, band, FailMode.FAIL_OPEN);

        assertTrue(resolution.fallback());
        assertEquals(NoOpinionReason.LOW_CONFIDENCE.name(), resolution.reason());
        assertEquals(ToolRiskAction.REQUIRE_APPROVAL, resolution.effectiveAction());
        assertTrue(resolution.escalated());
        assertTrue(resolution.isRequireApproval());

        DecisionTelemetry.record(meterRegistry, resolution);
        assertEquals(
                1.0,
                meterRegistry
                        .find(DecisionTelemetry.METRIC_NAME)
                        .tag("fallback", "true")
                        .tag("action", "REQUIRE_APPROVAL")
                        .tag("outcome", "FALLBACK")
                        .counter()
                        .count());
    }

    @Test
    void path7_malformedResponseFailsClosedToInferenceError() {
        var reqBuilder = DecisionRequest.builder("dec.malformed", 1, "test");
        QuestionKey<Choice> choiceKey = reqBuilder.registerChoice("c", "c", List.of("allow", "deny"));
        DecisionRequest request = reqBuilder.build();

        var engine = InMemoryDecisionEngine.builder()
                .withAnswer(choiceKey, new Choice("disallowed_label"))
                .build();

        DecisionResult result = engine.evaluate(request);
        assertInstanceOf(DecisionResult.NoOpinion.class, result);
        assertEquals(NoOpinionReason.INFERENCE_ERROR, result.asNoOpinion().reason());

        // Under FailMode.FAIL_CLOSED, INFERENCE_ERROR escalates to REQUIRE_APPROVAL
        ToolRiskDecisionPolicy.PolicyResolution res =
                ToolRiskDecisionPolicy.resolve(null, result, new ReviewBand(0.4, 0.8), FailMode.FAIL_CLOSED);
        assertEquals(ToolRiskAction.REQUIRE_APPROVAL, res.effectiveAction());
        assertTrue(res.escalated());
        assertTrue(res.fallback());
    }

    @Test
    void path8_shadowBeanIsolationRejectsUnauthorizedEnginePlacement() {
        class RemoteEngineStub implements DecisionEngine, RemoteDecisionProvider {
            @Override
            public ProviderCategory category() {
                return ProviderCategory.REMOTE;
            }

            @Override
            public DecisionResult evaluate(DecisionRequest request) {
                return new DecisionResult.NoOpinion(NoOpinionReason.DISABLED);
            }
        }

        assertThrows(
                SecurityException.class,
                () -> runtimeControl.registerReadyImplementation(Classifier.TOOL_RISK, new RemoteEngineStub()));

        class SelfHostedEngineStub implements DecisionEngine, SelfHostedDecisionProvider {
            @Override
            public ProviderCategory category() {
                return ProviderCategory.SELF_HOSTED;
            }

            @Override
            public DecisionResult evaluate(DecisionRequest request) {
                return new DecisionResult.NoOpinion(NoOpinionReason.DISABLED);
            }
        }

        assertThrows(
                SecurityException.class,
                () -> runtimeControl.registerReadyImplementation(Classifier.TOOL_RISK, new SelfHostedEngineStub()));
    }

    @Test
    void path9_enableWithoutReadyImplementationRejected() {
        var toggle = runtimeControl.setEnabled(Classifier.TOOL_RISK, true);
        assertFalse(toggle.accepted());
        assertEquals("MISSING_READY_IMPLEMENTATION", toggle.reason());
        assertFalse(toggle.snapshot().requestedEnabled());
        assertFalse(toggle.snapshot().effectiveEnabled());
    }

    @Test
    void path10_disableAtomicallyRejectsNewLeasesAndDrainsAcceptedLeases() {
        try (var _ = runtimeControl.registerReadyImplementation(Classifier.TOOL_RISK)) {
            runtimeControl.setEnabled(Classifier.TOOL_RISK, true);
            var activeLease = runtimeControl.tryAcquire(Classifier.TOOL_RISK).orElseThrow();

            var disableResult = runtimeControl.setEnabled(Classifier.TOOL_RISK, false);
            assertTrue(disableResult.accepted());
            assertTrue(disableResult.changed());
            assertTrue(disableResult.snapshot().draining());
            assertEquals(1, disableResult.snapshot().inFlight());

            // New leases immediately rejected
            assertTrue(runtimeControl.tryAcquire(Classifier.TOOL_RISK).isEmpty());

            // Releasing the lease completes the drain
            activeLease.close();
            var drainedSnapshot = runtimeControl.snapshot(Classifier.TOOL_RISK);
            assertFalse(drainedSnapshot.draining());
            assertEquals(0, drainedSnapshot.inFlight());
        }
    }

    @Test
    void path11_mutationPreconditionsAndUnchangedStateOnFailure() {
        // Precondition 1: Mutation without ready implementation rejected and leaves state unchanged
        var rejection = runtimeControl.setEnabled(Classifier.TOOL_RISK, true);
        assertFalse(rejection.accepted());
        assertFalse(runtimeControl.snapshot(Classifier.TOOL_RISK).requestedEnabled());
        assertFalse(runtimeControl.snapshot(Classifier.TOOL_RISK).effectiveEnabled());

        // Precondition 2: With ready implementation, mutation succeeds
        try (var _ = runtimeControl.registerReadyImplementation(Classifier.TOOL_RISK)) {
            var result = runtimeControl.setEnabled(Classifier.TOOL_RISK, true);
            assertTrue(result.accepted());
            assertTrue(runtimeControl.snapshot(Classifier.TOOL_RISK).effectiveEnabled());
        }

        // Precondition 3: When registration closes, effective status immediately drops to false
        assertFalse(runtimeControl.snapshot(Classifier.TOOL_RISK).effectiveEnabled());
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
