package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.ArrayList;
import java.util.List;

import com.amsha.classifier.api.AnswerQualityAction;
import com.amsha.classifier.api.AnswerQualityContext;
import com.amsha.classifier.api.ClassifierResult;
import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class AnswerQualityDispatcherTest {

    private static final AnswerQualityContext CONTEXT =
            new AnswerQualityContext("researcher", "summarize", "A complete answer.", false);

    @Test
    void noImplementationReturnsMissingModelNoOpinion() {
        var dispatcher = new AnswerQualityDispatcher(List.of(), new SimpleMeterRegistry());

        ClassifierResult.NoOpinion<AnswerQualityAction> result =
                assertInstanceOf(ClassifierResult.NoOpinion.class, dispatcher.evaluate(CONTEXT));

        assertEquals(NoOpinionReason.MISSING_MODEL, result.reason());
    }

    @Test
    void ordersInterceptorsAndReturnsFirstRetryRequest() {
        List<String> order = new ArrayList<>();
        var dispatcher = new AnswerQualityDispatcher(
                List.of(
                        interceptor(
                                20,
                                "retry",
                                order,
                                new ClassifierResult.Opinion<>(AnswerQualityAction.REQUEST_RETRY, 0.91f, "quality")),
                        interceptor(
                                10, "none", order, new ClassifierResult.NoOpinion<>(NoOpinionReason.LOW_CONFIDENCE))),
                new SimpleMeterRegistry());

        ClassifierResult.Opinion<AnswerQualityAction> result =
                assertInstanceOf(ClassifierResult.Opinion.class, dispatcher.evaluate(CONTEXT));

        assertEquals(AnswerQualityAction.REQUEST_RETRY, result.value());
        assertEquals(List.of("none", "retry"), order);
    }

    @Test
    void failureIsIsolatedAndCountedWithoutExposingContext() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var dispatcher = new AnswerQualityDispatcher(
                List.of(new AnswerQualityInterceptor() {
                    @Override
                    public int priority() {
                        return 1;
                    }

                    @Override
                    public ClassifierResult<AnswerQualityAction> evaluate(AnswerQualityContext context) {
                        throw new IllegalStateException("private answer must not be logged");
                    }
                }),
                registry);

        ClassifierResult.NoOpinion<AnswerQualityAction> result =
                assertInstanceOf(ClassifierResult.NoOpinion.class, dispatcher.evaluate(CONTEXT));

        assertEquals(NoOpinionReason.INFERENCE_ERROR, result.reason());
        assertEquals(
                1.0,
                registry.get("amsha.classifier.answer_quality.active.interceptor.errors")
                        .tag("reason", "inference_error")
                        .counter()
                        .count());
    }

    private static AnswerQualityInterceptor interceptor(
            int priority, String name, List<String> order, ClassifierResult<AnswerQualityAction> result) {
        return new AnswerQualityInterceptor() {
            @Override
            public int priority() {
                return priority;
            }

            @Override
            public ClassifierResult<AnswerQualityAction> evaluate(AnswerQualityContext context) {
                order.add(name);
                return result;
            }
        };
    }
}
