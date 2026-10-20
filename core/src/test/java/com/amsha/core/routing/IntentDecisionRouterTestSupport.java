package com.amsha.core.routing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import com.amsha.classifier.api.Choice;
import com.amsha.classifier.api.DecisionEngine;
import com.amsha.classifier.api.DecisionRequest;
import com.amsha.classifier.api.DecisionResult;
import com.amsha.classifier.api.QuestionKey;

/**
 * Test-only CDI engine used to exercise real router config injection.
 *
 * <p>Deliberately {@code @Alternative} <b>without</b> {@code @Priority}. A prioritised alternative is
 * enabled globally across the test deployment, which would make
 * {@code Instance<DecisionEngine>.isResolvable()} true for every {@code @QuarkusTest} in this module.
 * That predicate is what {@code DecisionPlaneReadiness} reads to decide whether the decision plane is
 * inert, and what {@code DecisionShadowEngineResolver} reads before handing an engine to the shadow
 * classifiers, so a globally enabled mock would silently suppress the inert diagnostic everywhere
 * else. Enablement is scoped to the profiles that need it via
 * {@code QuarkusTestProfile.getEnabledAlternatives()}.
 *
 * <p>The produced engine is {@code @Singleton} so the instance injected into a test is the same one
 * {@code IntentDecisionRouter} obtains from {@code Instance.get()}. A {@code @Dependent} producer
 * would hand out a fresh mock per lookup, making {@code verify(engine, never())} vacuously true.
 */
@ApplicationScoped
@Alternative
public class IntentDecisionRouterTestSupport {

    @Produces
    @Singleton
    @SuppressWarnings("unchecked")
    DecisionEngine decisionEngine() {
        DecisionEngine engine = mock(DecisionEngine.class);
        when(engine.evaluate(any())).thenAnswer(invocation -> {
            DecisionRequest request = invocation.getArgument(0);
            QuestionKey<Choice> key = (QuestionKey<Choice>) request.questions()
                    .get(IntentDecisionRouter.QUESTION_HANDLE)
                    .key();
            return DecisionResult.opinionBuilder(request.decisionId(), request.schemaVersion())
                    .put(key, new Choice("coder", 0.92))
                    .ruleId("test-fixture")
                    .build();
        });
        return engine;
    }
}
