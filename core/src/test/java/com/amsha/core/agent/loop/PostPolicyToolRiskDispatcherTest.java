package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import com.amsha.core.classifier.ClassifierResult;
import com.amsha.core.classifier.ClassifierResult.NoOpinionReason;
import com.amsha.security.api.AutonomyLevel;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class PostPolicyToolRiskDispatcherTest {

    private static final PostPolicyToolContext CONTEXT = new PostPolicyToolContext(
            "coder", AutonomyLevel.FULL, "sandbox_exec", Map.of("language", "java"), "run code", false);

    @Test
    void noImplementationReturnsMissingModelNoOpinion() {
        var dispatcher = new PostPolicyToolRiskDispatcher(List.of(), new SimpleMeterRegistry());

        ClassifierResult.NoOpinion<ToolRiskAction> result =
                assertInstanceOf(ClassifierResult.NoOpinion.class, dispatcher.evaluate(CONTEXT));

        assertEquals(NoOpinionReason.MISSING_MODEL, result.reason());
    }

    @Test
    void composesOrderedOpinionsMonotonically() {
        List<String> order = new ArrayList<>();
        var dispatcher = new PostPolicyToolRiskDispatcher(
                List.of(
                        interceptor(30, "block", order, opinion(ToolRiskAction.BLOCK, "block")),
                        interceptor(10, "none", order, new ClassifierResult.NoOpinion<>(NoOpinionReason.DISABLED)),
                        interceptor(20, "approval", order, opinion(ToolRiskAction.REQUIRE_APPROVAL, "approval"))),
                new SimpleMeterRegistry());

        ClassifierResult.Opinion<ToolRiskAction> result =
                assertInstanceOf(ClassifierResult.Opinion.class, dispatcher.evaluate(CONTEXT));

        assertEquals(ToolRiskAction.BLOCK, result.value());
        assertEquals(List.of("none", "approval", "block"), order);
    }

    @Test
    void timeoutIsIsolatedAndLaterOpinionStillApplies() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var dispatcher = new PostPolicyToolRiskDispatcher(
                List.of(
                        new PostPolicyToolRiskInterceptor() {
                            @Override
                            public int priority() {
                                return 1;
                            }

                            @Override
                            public ClassifierResult<ToolRiskAction> evaluate(PostPolicyToolContext context) {
                                throw new IllegalStateException(new TimeoutException("late"));
                            }
                        },
                        interceptor(
                                2,
                                "approval",
                                new ArrayList<>(),
                                opinion(ToolRiskAction.REQUIRE_APPROVAL, "approval"))),
                registry);

        ClassifierResult.Opinion<ToolRiskAction> result =
                assertInstanceOf(ClassifierResult.Opinion.class, dispatcher.evaluate(CONTEXT));

        assertEquals(ToolRiskAction.REQUIRE_APPROVAL, result.value());
        assertEquals(
                1.0,
                registry.get("amsha.classifier.tool_risk.active.interceptor.errors")
                        .tag("reason", "timeout")
                        .counter()
                        .count());
    }

    @Test
    void equalPrioritiesUseBinaryClassNameTieBreaker() {
        List<String> order = new ArrayList<>();
        var dispatcher = new PostPolicyToolRiskDispatcher(
                List.of(new ZuluInterceptor(order), new AlphaInterceptor(order)), new SimpleMeterRegistry());

        dispatcher.evaluate(CONTEXT);

        assertEquals(List.of("alpha", "zulu"), order);
    }

    private static ClassifierResult.Opinion<ToolRiskAction> opinion(ToolRiskAction action, String ruleId) {
        return new ClassifierResult.Opinion<>(action, 0.9f, ruleId);
    }

    private static PostPolicyToolRiskInterceptor interceptor(
            int priority, String name, List<String> order, ClassifierResult<ToolRiskAction> result) {
        return new PostPolicyToolRiskInterceptor() {
            @Override
            public int priority() {
                return priority;
            }

            @Override
            public ClassifierResult<ToolRiskAction> evaluate(PostPolicyToolContext context) {
                order.add(name);
                return result;
            }
        };
    }

    private static final class AlphaInterceptor implements PostPolicyToolRiskInterceptor {
        private final List<String> order;

        private AlphaInterceptor(List<String> order) {
            this.order = order;
        }

        @Override
        public int priority() {
            return 10;
        }

        @Override
        public ClassifierResult<ToolRiskAction> evaluate(PostPolicyToolContext context) {
            order.add("alpha");
            return new ClassifierResult.NoOpinion<>(NoOpinionReason.DISABLED);
        }
    }

    private static final class ZuluInterceptor implements PostPolicyToolRiskInterceptor {
        private final List<String> order;

        private ZuluInterceptor(List<String> order) {
            this.order = order;
        }

        @Override
        public int priority() {
            return 10;
        }

        @Override
        public ClassifierResult<ToolRiskAction> evaluate(PostPolicyToolContext context) {
            order.add("zulu");
            return new ClassifierResult.NoOpinion<>(NoOpinionReason.DISABLED);
        }
    }
}
