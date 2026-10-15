package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.amsha.classifier.api.Choice;
import com.amsha.classifier.api.ClassifierResult;
import com.amsha.classifier.api.DecisionRequest;
import com.amsha.classifier.api.DecisionResult;
import com.amsha.classifier.api.FailMode;
import com.amsha.classifier.api.InMemoryDecisionEngine;
import com.amsha.classifier.api.PostPolicyToolContext;
import com.amsha.classifier.api.QuestionKey;
import com.amsha.classifier.api.ToolRiskAction;
import com.amsha.classifier.api.ToolRiskDecisionPolicy;
import com.amsha.core.agent.role.CapabilityRegistry;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.classifier.DecisionTelemetry;
import com.amsha.core.hitl.ApprovalLeaseStore;
import com.amsha.core.security.AutonomyPolicy;
import com.amsha.core.tool.ToolGuardInterceptor;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.ToolOperation;
import com.amsha.tool.api.AgentTool;
import com.amsha.tool.api.IdempotencyMode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * End-to-end integration tests verifying that post-policy tool risk resolutions
 * flow through PostPolicyToolRiskDispatcher, ToolRiskDecisionPolicy, and DecisionTelemetry
 * into ToolInvocationGuard decisions (PauseForApproval and Blocked).
 */
class ToolInvocationGuardPostPolicyRiskTest {

    private ToolInvocationGuard guard;
    private SimpleMeterRegistry meterRegistry;
    private ToolRegistry mockToolRegistry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        guard = new ToolInvocationGuard();

        RoleRegistry roleRegistry = mock(RoleRegistry.class);
        mockToolRegistry = mock(ToolRegistry.class);
        CapabilityRegistry capabilityRegistry = mock(CapabilityRegistry.class);
        when(capabilityRegistry.hasCapability(anyString(), anyString())).thenReturn(true);

        Instance<ToolGuardInterceptor> mockInterceptors = mock(Instance.class);
        when(mockInterceptors.iterator()).thenReturn(Collections.emptyIterator());
        when(mockInterceptors.stream()).thenReturn(Stream.empty());

        guard.autonomyPolicy = new AutonomyPolicy.Standard();
        guard.approvalLeaseStore = mock(ApprovalLeaseStore.class);
        when(guard.approvalLeaseStore.hasActiveLease(anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(false);

        guard.roleRegistry = roleRegistry;
        guard.objectMapper = new ObjectMapper();
        guard.toolRegistry = mockToolRegistry;
        guard.capabilityRegistry = capabilityRegistry;
        guard.interceptors = mockInterceptors;

        guard.dynamicToolAdmissionLookup = mock(Instance.class);
        when(guard.dynamicToolAdmissionLookup.isUnsatisfied()).thenReturn(true);

        var mockTool = mock(AgentTool.class);
        when(mockTool.idempotencyMode()).thenReturn(IdempotencyMode.IDEMPOTENT);
        when(mockTool.requiredOperation()).thenReturn(ToolOperation.READ_FS);
        when(mockToolRegistry.findTool(any())).thenReturn(Optional.of(mockTool));

        guard.init();
    }

    @Test
    void postPolicyRiskRequireApprovalPausesForApprovalAndEmitsTelemetry() {
        AtomicReference<ToolOperation> observedOperation = new AtomicReference<>();
        PostPolicyToolRiskInterceptor interceptor = new PostPolicyToolRiskInterceptor() {
            @Override
            public int priority() {
                return 10;
            }

            @Override
            public ClassifierResult<ToolRiskAction> evaluate(PostPolicyToolContext context) {
                observedOperation.set(context.requiredOperation());
                return new ClassifierResult.Opinion<>(ToolRiskAction.REQUIRE_APPROVAL, 0.9f, "rule-hitl");
            }
        };

        guard.postPolicyToolRiskDispatcher = new PostPolicyToolRiskDispatcher(List.of(interceptor), meterRegistry);

        ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                "s-hitl",
                "coder",
                AutonomyLevel.FULL,
                "read_file",
                Map.of("path", "file.txt"),
                List.of(),
                "read",
                List.of());

        assertInstanceOf(ToolInvocationGuard.GuardDecision.PauseForApproval.class, decision);
        assertEquals(ToolOperation.READ_FS, observedOperation.get());

        Counter counter = meterRegistry.find(DecisionTelemetry.METRIC_NAME).counter();
        assertNotNull(counter, "Decision telemetry metric must be recorded");
        assertEquals(1.0, counter.count());
        assertEquals("REQUIRE_APPROVAL", counter.getId().getTag("action"));
        assertEquals("true", counter.getId().getTag("escalated"));
        assertEquals("false", counter.getId().getTag("fallback"));
        assertEquals("REQUIRE_APPROVAL", counter.getId().getTag("outcome"));
    }

    @Test
    void postPolicyRiskBlockPreventsExecutionAndEmitsTelemetry() {
        PostPolicyToolRiskInterceptor interceptor = new PostPolicyToolRiskInterceptor() {
            @Override
            public int priority() {
                return 10;
            }

            @Override
            public ClassifierResult<ToolRiskAction> evaluate(PostPolicyToolContext context) {
                return new ClassifierResult.Opinion<>(ToolRiskAction.BLOCK, 0.95f, "rule-block");
            }
        };

        guard.postPolicyToolRiskDispatcher = new PostPolicyToolRiskDispatcher(List.of(interceptor), meterRegistry);

        ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                "s-block",
                "coder",
                AutonomyLevel.FULL,
                "read_file",
                Map.of("path", "secrets.txt"),
                List.of(),
                "read",
                List.of());

        assertInstanceOf(ToolInvocationGuard.GuardDecision.Blocked.class, decision);

        Counter counter = meterRegistry.find(DecisionTelemetry.METRIC_NAME).counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
        assertEquals("BLOCK", counter.getId().getTag("action"));
        assertEquals("true", counter.getId().getTag("escalated"));
        assertEquals("false", counter.getId().getTag("fallback"));
        assertEquals("BLOCK", counter.getId().getTag("outcome"));
    }

    @Test
    void postPolicyRiskBlockWithoutConfidenceSurvivesAndBlocksExecution() {
        var engine = InMemoryDecisionEngine.ofRaw(Map.of("answers", Map.of("tool_risk", Map.of("choice", "BLOCK"))));

        PostPolicyToolRiskInterceptor interceptor = new PostPolicyToolRiskInterceptor() {
            @Override
            public int priority() {
                return 10;
            }

            @Override
            public ClassifierResult<ToolRiskAction> evaluate(PostPolicyToolContext context) {
                return new ClassifierResult.Opinion<>(ToolRiskAction.BLOCK, 1.0f, "rule-block");
            }

            @Override
            public DecisionResult evaluateDecision(PostPolicyToolContext context) {
                var reqBuilder = DecisionRequest.builder("tool.risk.post_policy", 1, context.toolName());
                QuestionKey<Choice> key = reqBuilder.registerChoice(
                        "tool_risk", "tool_risk", List.of("ALLOW", "REQUIRE_APPROVAL", "BLOCK"));
                DecisionResult decResult = engine.evaluate(reqBuilder.build());
                assertTrue(((DecisionResult.Opinion) decResult)
                        .get(key)
                        .confidence()
                        .isEmpty());
                return decResult;
            }
        };

        guard.postPolicyToolRiskDispatcher = new PostPolicyToolRiskDispatcher(List.of(interceptor), meterRegistry);

        ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                "s-block-noconf",
                "coder",
                AutonomyLevel.FULL,
                "read_file",
                Map.of("path", "sensitive.txt"),
                List.of(),
                "read",
                List.of());

        assertInstanceOf(ToolInvocationGuard.GuardDecision.Blocked.class, decision);

        Counter counter = meterRegistry.find(DecisionTelemetry.METRIC_NAME).counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
        assertEquals("BLOCK", counter.getId().getTag("action"));
        assertEquals("true", counter.getId().getTag("escalated"));
        assertEquals("false", counter.getId().getTag("fallback"));
        assertEquals("BLOCK", counter.getId().getTag("outcome"));
    }

    @Test
    void postPolicyReviewBandEscalatesBlockInMediumBandToPauseForApproval() {
        // Confidence 0.6 is in review band [0.4, 0.8), resolving to REQUIRE_APPROVAL via policy
        PostPolicyToolRiskInterceptor interceptor = new PostPolicyToolRiskInterceptor() {
            @Override
            public int priority() {
                return 10;
            }

            @Override
            public ClassifierResult<ToolRiskAction> evaluate(PostPolicyToolContext context) {
                return new ClassifierResult.Opinion<>(ToolRiskAction.BLOCK, 0.6f, "rule-block");
            }
        };

        guard.postPolicyToolRiskDispatcher = new PostPolicyToolRiskDispatcher(List.of(interceptor), meterRegistry);

        ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                "s-review-band",
                "coder",
                AutonomyLevel.FULL,
                "read_file",
                Map.of("path", "sensitive.txt"),
                List.of(),
                "read",
                List.of());

        assertInstanceOf(ToolInvocationGuard.GuardDecision.PauseForApproval.class, decision);

        Counter counter = meterRegistry.find(DecisionTelemetry.METRIC_NAME).counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
        assertEquals("REQUIRE_APPROVAL", counter.getId().getTag("action"));
        assertEquals("true", counter.getId().getTag("escalated"));
        assertEquals("false", counter.getId().getTag("fallback"));
        assertEquals("REQUIRE_APPROVAL", counter.getId().getTag("outcome"));
    }

    @Test
    void postPolicyFailClosedOnLowConfidencePausesForApproval() {
        // Low confidence (0.2 < 0.4 review band threshold) triggers fallback
        PostPolicyToolRiskInterceptor interceptor = new PostPolicyToolRiskInterceptor() {
            @Override
            public int priority() {
                return 10;
            }

            @Override
            public ClassifierResult<ToolRiskAction> evaluate(PostPolicyToolContext context) {
                return new ClassifierResult.Opinion<>(ToolRiskAction.BLOCK, 0.2f, "rule-block");
            }
        };

        // PostPolicyToolRiskDispatcher configured with FAIL_CLOSED executes ToolRiskDecisionPolicy.resolve
        guard.postPolicyToolRiskDispatcher = new PostPolicyToolRiskDispatcher(
                List.of(interceptor), meterRegistry, ToolRiskDecisionPolicy.DEFAULT_REVIEW_BAND, FailMode.FAIL_CLOSED);

        ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                "s-failclosed",
                "coder",
                AutonomyLevel.FULL,
                "read_file",
                Map.of("path", "file.txt"),
                List.of(),
                "read",
                List.of());

        assertInstanceOf(ToolInvocationGuard.GuardDecision.PauseForApproval.class, decision);

        Counter counter = meterRegistry.find(DecisionTelemetry.METRIC_NAME).counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
        assertEquals("REQUIRE_APPROVAL", counter.getId().getTag("action"));
        assertEquals("true", counter.getId().getTag("escalated"));
        assertEquals("true", counter.getId().getTag("fallback"));
        assertEquals("FALLBACK", counter.getId().getTag("outcome"));
    }

    @Test
    void defaultFailOpenEscalatesLowConfidenceBlockToApproval() {
        PostPolicyToolRiskInterceptor interceptor = new PostPolicyToolRiskInterceptor() {
            @Override
            public int priority() {
                return 10;
            }

            @Override
            public ClassifierResult<ToolRiskAction> evaluate(PostPolicyToolContext context) {
                return new ClassifierResult.Opinion<>(ToolRiskAction.BLOCK, 0.2f, "low-confidence-block");
            }

            @Override
            public DecisionResult evaluateDecision(PostPolicyToolContext context) {
                QuestionKey<Choice> key = QuestionKey.of("tool_risk", Choice.class);
                return DecisionResult.opinionBuilder(PostPolicyToolRiskDispatcher.DECISION_ID, 1)
                        .put(key, new Choice("BLOCK", Map.of(), OptionalDouble.of(0.2)))
                        .ruleId("low-confidence-block")
                        .build();
            }
        };

        guard.postPolicyToolRiskDispatcher = new PostPolicyToolRiskDispatcher(List.of(interceptor), meterRegistry);

        ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                "s-low-confidence",
                "coder",
                AutonomyLevel.FULL,
                "read_file",
                Map.of("path", "file.txt"),
                List.of(),
                "read",
                List.of());

        assertInstanceOf(ToolInvocationGuard.GuardDecision.PauseForApproval.class, decision);
    }

    @Test
    void foreignDecisionBlockIsNotAppliedToTheToolRiskSite() {
        PostPolicyToolRiskInterceptor interceptor = new PostPolicyToolRiskInterceptor() {
            @Override
            public int priority() {
                return 10;
            }

            @Override
            public ClassifierResult<ToolRiskAction> evaluate(PostPolicyToolContext context) {
                return new ClassifierResult.Opinion<>(ToolRiskAction.BLOCK, 0.99f, "legacy");
            }

            @Override
            public DecisionResult evaluateDecision(PostPolicyToolContext context) {
                QuestionKey<Choice> key = QuestionKey.of("tool_risk", Choice.class);
                return DecisionResult.opinionBuilder("foreign.decision", 1)
                        .put(key, new Choice("BLOCK", Map.of(), OptionalDouble.of(0.99)))
                        .ruleId("foreign-block")
                        .build();
            }
        };

        guard.postPolicyToolRiskDispatcher = new PostPolicyToolRiskDispatcher(List.of(interceptor), meterRegistry);

        ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                "s-foreign-decision",
                "coder",
                AutonomyLevel.FULL,
                "read_file",
                Map.of("path", "file.txt"),
                List.of(),
                "read",
                List.of());

        assertInstanceOf(ToolInvocationGuard.GuardDecision.Allow.class, decision);
    }

    @Test
    void postPolicyDisabledOrAllowPreservesAllowDecision() {
        PostPolicyToolRiskInterceptor disabledInterceptor = new PostPolicyToolRiskInterceptor() {
            @Override
            public int priority() {
                return 10;
            }

            @Override
            public ClassifierResult<ToolRiskAction> evaluate(PostPolicyToolContext context) {
                return new ClassifierResult.NoOpinion<>(ClassifierResult.NoOpinionReason.DISABLED);
            }
        };

        guard.postPolicyToolRiskDispatcher =
                new PostPolicyToolRiskDispatcher(List.of(disabledInterceptor), meterRegistry);

        ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                "s-allow",
                "coder",
                AutonomyLevel.FULL,
                "read_file",
                Map.of("path", "file.txt"),
                List.of(),
                "read",
                List.of());

        assertInstanceOf(ToolInvocationGuard.GuardDecision.Allow.class, decision);

        Counter counter = meterRegistry.find(DecisionTelemetry.METRIC_NAME).counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
        assertEquals("ALLOW", counter.getId().getTag("action"));
        assertEquals("false", counter.getId().getTag("escalated"));
        assertEquals("true", counter.getId().getTag("fallback"));
        assertEquals("FALLBACK", counter.getId().getTag("outcome"));
    }
}
