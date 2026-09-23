package com.amsha.classifier.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.transaction.Status;
import jakarta.transaction.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

/**
 * Focused contract tests for {@link InMemoryDecisionEngine}, {@link DecisionTransactionGuard},
 * and provider isolation through the real {@link DecisionEngine#evaluate(DecisionRequest)} boundary.
 */
class InMemoryDecisionEngineTest {

    @Test
    void deterministicEvaluationWithStaticAnswers() {
        var reqBuilder = DecisionRequest.builder("tool.risk", 1, "tool=bash,cmd=ls");
        QuestionKey<Choice> choiceKey =
                reqBuilder.registerChoice("risk_level", "Evaluate risk", List.of("low", "high"));
        QuestionKey<Noul> noulKey = reqBuilder.registerNoul("is_malicious", "Is malicious");
        DecisionRequest request = reqBuilder.build();

        var engine = InMemoryDecisionEngine.builder()
                .withAnswer(choiceKey, new Choice("low", OptionalDouble.of(0.9)))
                .withAnswer(noulKey, new Noul(0.05))
                .withRuleId("static-rule")
                .build();

        assertEquals(ProviderCategory.IN_PROCESS, engine.category());
        DecisionResult result = engine.evaluate(request);

        assertTrue(result.isOpinion());
        assertFalse(result.isNoOpinion());
        DecisionResult.Opinion opinion = result.asOpinion();
        assertEquals("tool.risk", opinion.decisionId());
        assertEquals("static-rule", opinion.ruleId());
        assertEquals("low", opinion.get(choiceKey).label());
        assertEquals(0.05, opinion.get(noulKey).probability(), 1e-6);
    }

    @Test
    void deterministicEvaluationWithStaticResult() {
        var reqBuilder = DecisionRequest.builder("eval.route", 1, "query=code");
        QuestionKey<Choice> roleKey = reqBuilder.registerChoice("role", "Select role", List.of("coder", "reviewer"));
        DecisionRequest request = reqBuilder.build();

        var opinion = DecisionResult.opinionBuilder("eval.route", 1)
                .put(roleKey, new Choice("coder"))
                .ruleId("custom-evaluator-rule")
                .build();

        var engine = InMemoryDecisionEngine.ofResult(opinion);

        DecisionResult result = engine.evaluate(request);
        assertTrue(result.isOpinion());
        assertEquals("coder", result.asOpinion().get(roleKey).label());
        assertTrue(result.asOpinion().get(roleKey).confidence().isEmpty());
    }

    @Test
    void rawPayloadPreservesAbsentFieldsThroughEngineSeam() {
        var reqBuilder = DecisionRequest.builder("absent.fields", 1, "state");
        QuestionKey<Choice> choiceKey = reqBuilder.registerChoice("action", "Select action", List.of("allow", "deny"));
        QuestionKey<Score> scoreKey =
                reqBuilder.registerScore("rubric", "Grade rubric", List.of("1", "2", "3", "4", "5"));
        DecisionRequest request = reqBuilder.build();

        Map<String, Object> raw = Map.of(
                "answers",
                Map.of(
                        "action", Map.of("choice", "allow"),
                        "rubric", Map.of("score", 3)));

        DecisionEngine engine = InMemoryDecisionEngine.ofRaw(raw);
        DecisionResult result = engine.evaluate(request);

        assertTrue(result.isOpinion());
        DecisionResult.Opinion op = result.asOpinion();
        assertNull(op.ruleId());
        assertTrue(op.get(choiceKey).confidence().isEmpty());
        assertTrue(op.get(choiceKey).probabilities().isEmpty());
        assertTrue(op.get(scoreKey).confidence().isEmpty());
        assertTrue(op.get(scoreKey).probabilities().isEmpty());
        assertEquals(5, op.get(scoreKey).legend().size());
    }

    @Test
    void malformedAndPartialPayloadFailsClosedToInferenceError() {
        var reqBuilder = DecisionRequest.builder("malformed.dec", 1, "state");
        reqBuilder.registerChoice("c1", "c1", List.of("a", "b"));
        reqBuilder.registerChoice("c2", "c2", List.of("x", "y"));
        DecisionRequest request = reqBuilder.build();

        // 1. Partial answers (missing c2)
        DecisionEngine partialEngine =
                InMemoryDecisionEngine.ofRaw(Map.of("answers", Map.of("c1", Map.of("choice", "a"))));
        DecisionResult partialRes = partialEngine.evaluate(request);
        assertTrue(partialRes.isNoOpinion());
        assertEquals(
                ClassifierResult.NoOpinionReason.INFERENCE_ERROR,
                partialRes.asNoOpinion().reason());
        assertTrue(partialRes.asNoOpinion().optionalDetail().isPresent());

        // 2. Malformed answers structure
        DecisionEngine malformedEngine = InMemoryDecisionEngine.ofRaw(Map.of("answers", "not-a-map"));
        DecisionResult malformedRes = malformedEngine.evaluate(request);
        assertTrue(malformedRes.isNoOpinion());
        assertEquals(
                ClassifierResult.NoOpinionReason.INFERENCE_ERROR,
                malformedRes.asNoOpinion().reason());
    }

    @Test
    void wrongPrimitiveFailsClosedToInferenceError() {
        var reqBuilder = DecisionRequest.builder("wrong.primitive", 1, "state");
        reqBuilder.registerNoul("prob", "Probability question");
        DecisionRequest request = reqBuilder.build();

        // Provided choice map instead of noul probability
        DecisionEngine engine =
                InMemoryDecisionEngine.ofRaw(Map.of("answers", Map.of("prob", Map.of("choice", "high"))));
        DecisionResult res = engine.evaluate(request);
        assertTrue(res.isNoOpinion());
        assertEquals(
                ClassifierResult.NoOpinionReason.INFERENCE_ERROR,
                res.asNoOpinion().reason());
    }

    @Test
    void unknownReferencesAndExtraHandlesFailClosed() {
        var reqBuilder = DecisionRequest.builder("unknown.ref", 1, "state");
        reqBuilder.registerChoice("c1", "Choice 1", List.of("a", "b"));
        DecisionRequest request = reqBuilder.build();

        DecisionEngine engine = InMemoryDecisionEngine.ofRaw(Map.of(
                "answers",
                Map.of(
                        "c1", Map.of("choice", "a"),
                        "rogue_handle", Map.of("choice", "surprise"))));
        DecisionResult res = engine.evaluate(request);
        assertTrue(res.isNoOpinion());
        assertEquals(
                ClassifierResult.NoOpinionReason.INFERENCE_ERROR,
                res.asNoOpinion().reason());
        assertTrue(res.asNoOpinion().optionalDetail().get().contains("rogue_handle"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonStringProviderKeysFailClosedWithoutLeakingKeyValues() {
        var reqBuilder = DecisionRequest.builder("malformed.keys", 1, "state");
        reqBuilder.registerChoice("c1", "Choice 1", List.of("a", "b"));
        DecisionRequest request = reqBuilder.build();

        Map<String, Object> malformedEnvelope = (Map<String, Object>) (Map<?, ?>) Map.of(7319, "synthetic-marker");
        DecisionResult topLevel = DecisionPayloadDecoder.decode(request, malformedEnvelope);
        assertTrue(topLevel.isNoOpinion());
        assertEquals(
                ClassifierResult.NoOpinionReason.INFERENCE_ERROR,
                topLevel.asNoOpinion().reason());
        assertFalse(topLevel.asNoOpinion().optionalDetail().orElseThrow().contains("7319"));

        DecisionResult nested =
                DecisionPayloadDecoder.decode(request, Map.of("answers", Map.of(7319, Map.of("choice", "a"))));
        assertTrue(nested.isNoOpinion());
        assertEquals(
                ClassifierResult.NoOpinionReason.INFERENCE_ERROR,
                nested.asNoOpinion().reason());
        assertFalse(nested.asNoOpinion().optionalDetail().orElseThrow().contains("7319"));
    }

    @Test
    void legacyPayloadMappingPreservesAbsenceWithoutInventedCertainty() {
        var reqBuilder = DecisionRequest.builder("legacy.compat", 1, "state");
        QuestionKey<Choice> singleKey = reqBuilder.registerChoice("dest", "Destination", List.of("destA", "destB"));
        DecisionRequest request = reqBuilder.build();

        // Legacy response without confidence
        DecisionEngine engine = InMemoryDecisionEngine.ofRaw(Map.of("value", "destA"));
        DecisionResult res = engine.evaluate(request);
        assertTrue(res.isOpinion());
        assertEquals("destA", res.asOpinion().get(singleKey).label());
        assertTrue(res.asOpinion().get(singleKey).confidence().isEmpty());
        assertNull(res.asOpinion().ruleId());

        // Legacy response with explicit confidence
        DecisionEngine engine2 = InMemoryDecisionEngine.ofRaw(Map.of("value", "destB", "confidence", 0.77));
        DecisionResult res2 = engine2.evaluate(request);
        assertTrue(res2.isOpinion());
        assertEquals("destB", res2.asOpinion().get(singleKey).label());
        assertTrue(res2.asOpinion().get(singleKey).confidence().isPresent());
        assertEquals(0.77, res2.asOpinion().get(singleKey).confidence().orElseThrow(), 1e-6);
    }

    @Test
    void transactionGuardFailFastExceptionRejectsAndPreventsEvaluatorInvocation() {
        var reqBuilder = DecisionRequest.builder("tx.guard.test", 1, "state");
        QuestionKey<Choice> cKey = reqBuilder.registerChoice("c", "c", List.of("ok", "cancel"));
        DecisionRequest request = reqBuilder.build();

        AtomicInteger transactionStatus = new AtomicInteger(Status.STATUS_ACTIVE);
        var guard = new DecisionTransactionGuard(transactionStatus::get);

        var engine = InMemoryDecisionEngine.builder()
                .withTransactionGuard(guard)
                .withTransactionViolationPolicy(TransactionViolationPolicy.FAIL_FAST_EXCEPTION)
                .withAnswer(cKey, new Choice("ok"))
                .build();

        // 1. In active transaction -> fail fast with IllegalStateException
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> engine.evaluate(request));
        assertTrue(ex.getMessage().contains("Active JTA transaction detected"));

        // Verify diagnostic is strictly payload-free
        assertFalse(ex.getMessage().contains("tx.guard.test"));
        assertFalse(ex.getMessage().contains("state"));

        // 2. Set to transaction-free -> succeeds and returns registered answer
        transactionStatus.set(Status.STATUS_NO_TRANSACTION);
        DecisionResult res = engine.evaluate(request);
        assertTrue(res.isOpinion());
        assertEquals("ok", res.asOpinion().get(cKey).label());
    }

    @Test
    void failFastUsesOneStatusReadAndRejectsTheObservedActiveTransaction() {
        var reqBuilder = DecisionRequest.builder("tx.guard.single-read", 1, "state");
        QuestionKey<Choice> choiceKey = reqBuilder.registerChoice("c", "c", List.of("ok", "cancel"));
        DecisionRequest request = reqBuilder.build();
        AtomicInteger reads = new AtomicInteger();
        var guard = new DecisionTransactionGuard(
                () -> reads.getAndIncrement() == 0 ? Status.STATUS_ACTIVE : Status.STATUS_NO_TRANSACTION);
        var engine = InMemoryDecisionEngine.builder()
                .withTransactionGuard(guard)
                .withTransactionViolationPolicy(TransactionViolationPolicy.FAIL_FAST_EXCEPTION)
                .withAnswer(choiceKey, new Choice("ok"))
                .build();

        assertThrows(IllegalStateException.class, () -> engine.evaluate(request));
        assertEquals(1, reads.get());
    }

    @Test
    void transactionGuardFailClosedNoOpinionRejectsAndPreventsEvaluatorInvocation() {
        var reqBuilder = DecisionRequest.builder("tx.guard.noopinion", 1, "state");
        QuestionKey<Choice> cKey = reqBuilder.registerChoice("c", "c", List.of("ok", "cancel"));
        DecisionRequest request = reqBuilder.build();

        var guard = new DecisionTransactionGuard(() -> Status.STATUS_COMMITTING);

        var engine = InMemoryDecisionEngine.builder()
                .withTransactionGuard(guard)
                .withTransactionViolationPolicy(TransactionViolationPolicy.FAIL_CLOSED_NO_OPINION)
                .withAnswer(cKey, new Choice("ok"))
                .build();

        DecisionResult result = engine.evaluate(request);
        assertTrue(result.isNoOpinion());
        assertEquals(
                ClassifierResult.NoOpinionReason.OVERLOAD_REJECTED,
                result.asNoOpinion().reason());
        assertTrue(result.asNoOpinion().optionalDetail().get().contains("Active JTA transaction detected"));

        // When transaction-free -> succeeds
        var freeEngine = InMemoryDecisionEngine.builder()
                .withTransactionGuard(new DecisionTransactionGuard(() -> Status.STATUS_NO_TRANSACTION))
                .withTransactionViolationPolicy(TransactionViolationPolicy.FAIL_CLOSED_NO_OPINION)
                .withAnswer(cKey, new Choice("ok"))
                .build();
        DecisionResult freeResult = freeEngine.evaluate(request);
        assertTrue(freeResult.isOpinion());
        assertEquals("ok", freeResult.asOpinion().get(cKey).label());
    }

    @Test
    void unknownTransactionStatusRejectsEvaluationUnderBothPolicies() {
        var reqBuilder = DecisionRequest.builder("tx.guard.unknown", 1, "state");
        QuestionKey<Choice> choiceKey = reqBuilder.registerChoice("c", "c", List.of("ok", "cancel"));
        DecisionRequest request = reqBuilder.build();
        var guard = new DecisionTransactionGuard(() -> Status.STATUS_UNKNOWN);

        var failFast = InMemoryDecisionEngine.builder()
                .withTransactionGuard(guard)
                .withTransactionViolationPolicy(TransactionViolationPolicy.FAIL_FAST_EXCEPTION)
                .withAnswer(choiceKey, new Choice("ok"))
                .build();
        assertThrows(IllegalStateException.class, () -> failFast.evaluate(request));

        var failClosed = InMemoryDecisionEngine.builder()
                .withTransactionGuard(guard)
                .withTransactionViolationPolicy(TransactionViolationPolicy.FAIL_CLOSED_NO_OPINION)
                .withAnswer(choiceKey, new Choice("ok"))
                .build();
        DecisionResult result = failClosed.evaluate(request);
        assertTrue(result.isNoOpinion());
        assertEquals(
                ClassifierResult.NoOpinionReason.OVERLOAD_REJECTED,
                result.asNoOpinion().reason());
    }

    @Test
    void transactionStatusesProperlyClassified() {
        assertTrue(DecisionTransactionGuard.isTransactionActive(Status.STATUS_ACTIVE));
        assertTrue(DecisionTransactionGuard.isTransactionActive(Status.STATUS_PREPARING));
        assertTrue(DecisionTransactionGuard.isTransactionActive(Status.STATUS_PREPARED));
        assertTrue(DecisionTransactionGuard.isTransactionActive(Status.STATUS_COMMITTING));
        assertTrue(DecisionTransactionGuard.isTransactionActive(Status.STATUS_ROLLING_BACK));
        assertTrue(DecisionTransactionGuard.isTransactionActive(Status.STATUS_MARKED_ROLLBACK));

        assertFalse(DecisionTransactionGuard.isTransactionActive(Status.STATUS_NO_TRANSACTION));
        assertFalse(DecisionTransactionGuard.isTransactionActive(Status.STATUS_COMMITTED));
        assertFalse(DecisionTransactionGuard.isTransactionActive(Status.STATUS_ROLLEDBACK));
        assertTrue(DecisionTransactionGuard.isTransactionActive(Status.STATUS_UNKNOWN));
    }

    @Test
    void structuralFreedomFromExecutableCallbacks() {
        // Architecture & placement safety: InMemoryDecisionEngine must not hold any executable callback fields
        List<Class<?>> forbiddenCallbackTypes =
                List.of(Function.class, Consumer.class, Supplier.class, Callable.class, Runnable.class);

        for (Field field : InMemoryDecisionEngine.class.getDeclaredFields()) {
            for (Class<?> forbidden : forbiddenCallbackTypes) {
                assertFalse(
                        forbidden.isAssignableFrom(field.getType()),
                        "InMemoryDecisionEngine field " + field.getName() + " must NOT be a callback interface ("
                                + forbidden.getSimpleName() + ")");
            }
        }

        for (Method method : InMemoryDecisionEngine.class.getDeclaredMethods()) {
            for (Class<?> paramType : method.getParameterTypes()) {
                for (Class<?> forbidden : forbiddenCallbackTypes) {
                    assertFalse(
                            forbidden.isAssignableFrom(paramType),
                            "InMemoryDecisionEngine method " + method.getName()
                                    + " parameter must NOT be a callback interface (" + forbidden.getSimpleName()
                                    + ")");
                }
            }
        }
    }

    @Test
    void architecturalTransactionInvariantNoTransactionalAnnotations() {
        // Architecture invariant: no @Transactional on decision engine or guard classes/methods (§5.9)
        List<Class<?>> types = List.of(
                InMemoryDecisionEngine.class,
                DecisionEngine.class,
                InProcessDecisionEngine.class,
                DecisionTransactionGuard.class);

        for (Class<?> clazz : types) {
            assertNull(
                    clazz.getAnnotation(Transactional.class),
                    "Class " + clazz.getName() + " must NOT have @Transactional annotation");
            for (Method m : clazz.getDeclaredMethods()) {
                assertNull(
                        m.getAnnotation(Transactional.class),
                        "Method " + clazz.getSimpleName() + "." + m.getName() + " must NOT have @Transactional");
            }
        }
    }

    @Test
    void classifierSpiPlacementValidationSucceeds() {
        var engine = InMemoryDecisionEngine.builder().withRuleId("rule-1").build();

        InProcessDecisionEngine activated = engine.activateForClassifierSpi();
        assertSame(engine, activated);
        assertEquals(ProviderCategory.IN_PROCESS, engine.category());
        assertTrue(engine.category().permitsClassifierSpi());
    }

    @Test
    void nullRequestHandledDefensively() {
        var engine = InMemoryDecisionEngine.ofResult(
                new DecisionResult.NoOpinion(ClassifierResult.NoOpinionReason.DISABLED));
        assertThrows(NullPointerException.class, () -> engine.evaluate(null));
    }

    @Test
    void simulatedTimeoutAndFailureMappedCorrectly() {
        var reqBuilder = DecisionRequest.builder("timeout.test", 1, "state");
        reqBuilder.registerChoice("c", "c", List.of("1", "2"));
        DecisionRequest request = reqBuilder.build();

        var timeoutEngine = InMemoryDecisionEngine.ofSimulatedTimeout();
        DecisionResult timeoutRes = timeoutEngine.evaluate(request);
        assertTrue(timeoutRes.isNoOpinion());
        assertEquals(
                ClassifierResult.NoOpinionReason.TIMEOUT,
                timeoutRes.asNoOpinion().reason());
        assertEquals(
                "Evaluation timed out",
                timeoutRes.asNoOpinion().optionalDetail().orElse(""));

        var failureEngine = InMemoryDecisionEngine.builder()
                .withSimulatedFailure(ClassifierResult.NoOpinionReason.CIRCUIT_OPEN, "Circuit breaker tripped")
                .build();
        DecisionResult failureRes = failureEngine.evaluate(request);
        assertTrue(failureRes.isNoOpinion());
        assertEquals(
                ClassifierResult.NoOpinionReason.CIRCUIT_OPEN,
                failureRes.asNoOpinion().reason());
        assertEquals(
                "Circuit breaker tripped",
                failureRes.asNoOpinion().optionalDetail().orElse(""));
    }

    @Test
    void nestedPayloadMutationAfterConstructionDoesNotAffectEngine() {
        var reqBuilder = DecisionRequest.builder("immutable.test", 1, "state");
        QuestionKey<Choice> choiceKey = reqBuilder.registerChoice("c1", "c1", List.of("a", "b"));
        DecisionRequest request = reqBuilder.build();

        Map<String, Object> innerChoice = new HashMap<>();
        innerChoice.put("choice", "a");
        Map<String, Object> innerAnswers = new HashMap<>();
        innerAnswers.put("c1", innerChoice);
        Map<String, Object> mutableRaw = new HashMap<>();
        mutableRaw.put("answers", innerAnswers);

        DecisionEngine engine = InMemoryDecisionEngine.ofRaw(mutableRaw);

        // Mutate original nested map after engine creation
        innerChoice.put("choice", "mutated_value");
        innerAnswers.put("c2", Map.of("choice", "injected"));
        mutableRaw.put("answers", "corrupted");

        DecisionResult result = engine.evaluate(request);
        assertTrue(result.isOpinion());
        assertEquals("a", result.asOpinion().get(choiceKey).label());
    }

    @Test
    void configuredResultDecisionIdMismatchFailsClosed() {
        var reqBuilder = DecisionRequest.builder("expected.id", 1, "state");
        QuestionKey<Choice> choiceKey = reqBuilder.registerChoice("c", "c", List.of("1", "2"));
        var foreignOpinion = DecisionResult.opinionBuilder("mismatched.id", 1)
                .put(choiceKey, new Choice("1"))
                .build();

        DecisionResult res = InMemoryDecisionEngine.ofResult(foreignOpinion).evaluate(reqBuilder.build());
        assertTrue(res.isNoOpinion());
        assertEquals(
                ClassifierResult.NoOpinionReason.INFERENCE_ERROR,
                res.asNoOpinion().reason());
        assertTrue(res.asNoOpinion().optionalDetail().orElse("").contains("decisionId mismatch"));
    }

    @Test
    void configuredResultSchemaVersionMismatchFailsClosed() {
        var reqBuilder = DecisionRequest.builder("ver.test", 2, "state");
        QuestionKey<Choice> choiceKey = reqBuilder.registerChoice("c", "c", List.of("1", "2"));
        var opinionV1 = DecisionResult.opinionBuilder("ver.test", 1)
                .put(choiceKey, new Choice("1"))
                .build();

        DecisionResult res = InMemoryDecisionEngine.ofResult(opinionV1).evaluate(reqBuilder.build());
        assertTrue(res.isNoOpinion());
        assertEquals(
                ClassifierResult.NoOpinionReason.INFERENCE_ERROR,
                res.asNoOpinion().reason());
        assertTrue(res.asNoOpinion().optionalDetail().orElse("").contains("schemaVersion mismatch"));
    }

    @Test
    void configuredResultWithForeignHandleFailsClosed() {
        var reqBuilder1 = DecisionRequest.builder("handle.test", 1, "state");
        reqBuilder1.registerChoice("role", "role", List.of("a", "b"));
        var reqBuilder2 = DecisionRequest.builder("foreign.req", 1, "state");
        QuestionKey<Choice> foreignKey = reqBuilder2.registerChoice("role", "role", List.of("a", "b"));

        var foreignOpinion = DecisionResult.opinionBuilder("handle.test", 1)
                .put(foreignKey, new Choice("a"))
                .build();
        DecisionResult res = InMemoryDecisionEngine.ofResult(foreignOpinion).evaluate(reqBuilder1.build());

        assertTrue(res.isNoOpinion());
        assertEquals(
                ClassifierResult.NoOpinionReason.INFERENCE_ERROR,
                res.asNoOpinion().reason());
        assertTrue(res.asNoOpinion().optionalDetail().orElse("").contains("foreign or unknown question handle"));
    }

    @Test
    void configuredOpinionWithUnspecifiedDecisionIdFailsClosed() {
        var reqBuilder = DecisionRequest.builder("unspec.test", 1, "state");
        QuestionKey<Choice> choiceKey = reqBuilder.registerChoice("c", "c", List.of("1", "2"));
        var unspecifiedOpinion = DecisionResult.opinionBuilder("unspecified", 1)
                .put(choiceKey, new Choice("1"))
                .build();

        DecisionResult res = InMemoryDecisionEngine.ofResult(unspecifiedOpinion).evaluate(reqBuilder.build());
        assertTrue(res.isNoOpinion());
        assertEquals(
                ClassifierResult.NoOpinionReason.INFERENCE_ERROR,
                res.asNoOpinion().reason());
        assertTrue(res.asNoOpinion().optionalDetail().orElse("").contains("unspecified"));
    }

    @Test
    void malformedNestedNonStringKeysDoNotThrowClassCastExceptionDuringConstructionAndFailClosed() {
        var reqBuilder = DecisionRequest.builder("nonstring.keys", 1, "state");
        reqBuilder.registerChoice("c", "c", List.of("1", "2"));
        Map<Object, Object> malformedAnswers = new HashMap<>();
        malformedAnswers.put(12345, "invalid-key");
        Map<String, Object> raw = new HashMap<>();
        raw.put("answers", malformedAnswers);

        DecisionEngine engine = InMemoryDecisionEngine.ofRaw(raw);
        DecisionResult res = engine.evaluate(reqBuilder.build());
        assertTrue(res.isNoOpinion());
        assertEquals(
                ClassifierResult.NoOpinionReason.INFERENCE_ERROR,
                res.asNoOpinion().reason());
    }

    @Test
    void duplicateDecisionIdsAndKeysRejectedDuringEngineConstruction() {
        var builder = InMemoryDecisionEngine.builder();
        builder.withResult("dec.1", new DecisionResult.NoOpinion(ClassifierResult.NoOpinionReason.DISABLED));
        assertThrows(
                IllegalArgumentException.class,
                () -> builder.withResult(
                        "dec.1", new DecisionResult.NoOpinion(ClassifierResult.NoOpinionReason.DISABLED)));

        builder.withRawPayload("dec.raw", Map.of("value", "x"));
        assertThrows(IllegalArgumentException.class, () -> builder.withRawPayload("dec.raw", Map.of("value", "y")));

        QuestionKey<Choice> key = QuestionKey.choice("k1");
        builder.withAnswer(key, new Choice("x"));
        assertThrows(IllegalArgumentException.class, () -> builder.withAnswer(key, new Choice("y")));
    }
}
