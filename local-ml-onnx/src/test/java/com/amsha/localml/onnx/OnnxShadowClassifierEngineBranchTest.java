package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.onnxruntime.OrtEnvironment;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.amsha.classifier.api.ClassifierResult;
import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Coverage for the engine's inference path: the scheduled-task body, warmup, label mapping and
 * {@link OnnxClassifierInferenceException}-free failure translation.
 *
 * <p>{@code OnnxShadowClassifierEngineTest} covers the constructor validators, the pre-flight guards (blank model,
 * oversized input, open circuit) and the bundle-load / scheduler-rejection arms. What it never does is let the
 * scheduler actually <em>run</em> the task it was handed, so {@code run}, {@code runWarmup}, {@code warmUp} and
 * {@code mapResult} were entirely unexecuted. {@link #runTaskInline()} closes that gap by answering
 * {@code scheduler.submit} with a future completed from the task's own result.
 *
 * <p>The engine's {@code Duration} is the budget it passes to {@code future.get(timeout, MILLISECONDS)}, so the two
 * tests that need a real {@link java.util.concurrent.TimeoutException} build a short-timeout engine rather than
 * sleeping for the production default.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OnnxShadowClassifierEngineBranchTest {

    private static final String MODEL = "test-model";

    @Mock
    OnnxModelBundleRegistry bundleRegistry;

    @Mock
    OnnxRuntimeManager runtimeManager;

    @Mock
    OnnxSessionLeaseManager sessionLeaseManager;

    @Mock
    OnnxInferenceScheduler scheduler;

    @Mock
    OnnxTextClassifierRunner runner;

    @Mock
    OnnxClassifierCircuitBreaker circuitBreaker;

    @Mock
    OnnxClassifierCircuitBreaker.Permit permit;

    private VerifiedOnnxModelBundle bundle;
    private OnnxClassifierBundleSpec spec;
    private OnnxSessionLease lease;

    @BeforeEach
    void setUp() {
        bundle = mock(VerifiedOnnxModelBundle.class);
        OnnxModelBundleManifest manifest = mock(OnnxModelBundleManifest.class);
        spec = mock(OnnxClassifierBundleSpec.class);
        lease = mock(OnnxSessionLease.class);
        when(manifest.classifierSpec()).thenReturn(Optional.of(spec));
        when(bundle.manifest()).thenReturn(manifest);
    }

    // ===================== helpers =====================

    private OnnxShadowClassifierEngine<String> engine(Duration timeout, Set<String> requiredLabels) {
        return engine(timeout, requiredLabels, String::toUpperCase);
    }

    private OnnxShadowClassifierEngine<String> engine(
            Duration timeout, Set<String> requiredLabels, Function<String, String> labelMapper) {
        return new OnnxShadowClassifierEngine<>(
                bundleRegistry,
                runtimeManager,
                sessionLeaseManager,
                scheduler,
                runner,
                circuitBreaker,
                MODEL,
                timeout,
                1024L,
                3,
                labelMapper,
                requiredLabels);
    }

    /**
     * Makes {@code scheduler.submit} run the task it is given and complete the future with the task's own result
     * (or failure) — the behaviour the real scheduler provides on a worker thread, without the thread.
     */
    private void runTaskInline() throws Exception {
        when(scheduler.submit(any(Instant.class), any())).thenAnswer(invocation -> {
            Callable<?> task = invocation.getArgument(1, Callable.class);
            try {
                return CompletableFuture.completedFuture(task.call());
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        });
    }

    /** A scheduler that accepts the work but never completes it, forcing the engine's own deadline. */
    private void neverComplete() throws Exception {
        when(scheduler.submit(any(Instant.class), any())).thenReturn(new CompletableFuture<>());
    }

    /** Wires the parts of {@code classify} that precede the scheduler call. */
    private void acceptPermitAndBundle() throws Exception {
        when(circuitBreaker.tryAcquire()).thenReturn(Optional.of(permit));
        when(bundleRegistry.loadVerified(MODEL)).thenReturn(bundle);
        when(spec.labels()).thenReturn(List.of("label_a"));
    }

    @SuppressWarnings("unchecked")
    private static NoOpinionReason reason(ClassifierResult<String> result) {
        return ((ClassifierResult.NoOpinion<String>) result).reason();
    }

    // ===================== the scheduled task body =====================

    @Test
    void classifyRunsTheScheduledTaskWarmsUpAndMapsTheLabel() throws Exception {
        acceptPermitAndBundle();
        when(spec.warmupProbes()).thenReturn(List.of("probe one", "probe two"));
        when(runtimeManager.environment()).thenReturn(Optional.of(mock(OrtEnvironment.class)));
        when(sessionLeaseManager.acquire(eq(bundle), any(Instant.class))).thenReturn(lease);
        when(runner.classify(any(), eq(lease), any(), eq(spec), anyString(), any(Instant.class)))
                .thenReturn(new ClassifierResult.Opinion<>("label_a", 0.75f, "rule-1"));
        runTaskInline();

        ClassifierResult<String> result =
                engine(Duration.ofSeconds(2), Set.of()).classify("hello");

        // The validated label map is applied: label_a -> LABEL_A.
        assertEquals(new ClassifierResult.Opinion<>("LABEL_A", 0.75f, "rule-1"), result);
        verify(circuitBreaker).recordSuccess(permit);
        // Two warmup probes plus the real inference.
        verify(runner, times(3)).classify(any(), eq(lease), any(), eq(spec), anyString(), any(Instant.class));
        verify(lease).close();
    }

    @Test
    void classifyPassesANoOpinionResultStraightThrough() throws Exception {
        acceptPermitAndBundle();
        when(runtimeManager.environment()).thenReturn(Optional.of(mock(OrtEnvironment.class)));
        when(sessionLeaseManager.acquire(eq(bundle), any(Instant.class))).thenReturn(lease);
        when(runner.classify(any(), eq(lease), any(), eq(spec), anyString(), any(Instant.class)))
                .thenReturn(new ClassifierResult.NoOpinion<>(NoOpinionReason.LOW_CONFIDENCE));
        runTaskInline();

        ClassifierResult<String> result =
                engine(Duration.ofSeconds(2), Set.of()).classify("hello");

        assertEquals(new ClassifierResult.NoOpinion<>(NoOpinionReason.LOW_CONFIDENCE), result);
        verify(circuitBreaker).recordSuccess(permit);
    }

    @Test
    void classifyFailsClosedWhenTheRunnerEmitsALabelAbsentFromTheValidatedMap() throws Exception {
        acceptPermitAndBundle();
        when(runtimeManager.environment()).thenReturn(Optional.of(mock(OrtEnvironment.class)));
        when(sessionLeaseManager.acquire(eq(bundle), any(Instant.class))).thenReturn(lease);
        when(runner.classify(any(), eq(lease), any(), eq(spec), anyString(), any(Instant.class)))
                .thenReturn(new ClassifierResult.Opinion<>("mystery", 0.9f, "rule-1"));
        runTaskInline();

        ClassifierResult<String> result =
                engine(Duration.ofSeconds(2), Set.of()).classify("hello");

        assertEquals(NoOpinionReason.INFERENCE_ERROR, reason(result));
        verify(circuitBreaker).recordFailure(permit);
    }

    // ===================== scheduler rejection arms =====================

    @Test
    void classifyReportsInferenceErrorForAnUnrecognisedSchedulingFailure() throws Exception {
        acceptPermitAndBundle();
        when(scheduler.submit(any(Instant.class), any()))
                .thenThrow(new OnnxInferenceSchedulingException(
                        OnnxInferenceSchedulingException.Reason.SCHEDULER_STOPPED, "scheduler stopped"));

        ClassifierResult<String> result =
                engine(Duration.ofSeconds(2), Set.of()).classify("hello");

        // Neither the overload nor the timeout arm: the catch-all reason applies and the breaker counts a failure.
        assertEquals(NoOpinionReason.INFERENCE_ERROR, reason(result));
        verify(circuitBreaker).recordFailure(permit);
    }

    // ===================== the engine's own wait =====================

    @Test
    void classifyTimesOutWhenTheScheduledFutureNeverCompletes() throws Exception {
        acceptPermitAndBundle();
        neverComplete();

        ClassifierResult<String> result =
                engine(Duration.ofMillis(50), Set.of()).classify("hello");

        assertEquals(NoOpinionReason.TIMEOUT, reason(result));
        verify(circuitBreaker).recordFailure(permit);
    }

    @Test
    void classifyRestoresTheInterruptFlagWhenItsWaitIsInterrupted() throws Exception {
        acceptPermitAndBundle();
        neverComplete();

        ClassifierResult<String> result;
        boolean interruptRestored;
        Thread.currentThread().interrupt();
        try {
            result = engine(Duration.ofSeconds(2), Set.of()).classify("hello");
            interruptRestored = Thread.currentThread().isInterrupted();
        } finally {
            // get(...) consumes the flag; the engine must put it back, and the test must not leak it.
            Thread.interrupted();
        }

        assertEquals(NoOpinionReason.INFERENCE_ERROR, reason(result));
        assertTrue(interruptRestored, "the engine must restore the interrupt status it consumed");
        verify(circuitBreaker).recordNeutral(permit);
    }

    // ===================== failure translation =====================

    @Test
    void classifyTreatsASchedulingTimeoutCauseAsTimeout() throws Exception {
        acceptPermitAndBundle();
        when(scheduler.submit(any(Instant.class), any()))
                .thenReturn(CompletableFuture.failedFuture(new OnnxInferenceSchedulingException(
                        OnnxInferenceSchedulingException.Reason.TIMEOUT, "deadline exceeded")));

        ClassifierResult<String> result =
                engine(Duration.ofSeconds(2), Set.of()).classify("hello");

        assertEquals(NoOpinionReason.TIMEOUT, reason(result));
        verify(circuitBreaker).recordFailure(permit);
    }

    // ===================== ensureReady / warmup =====================

    @Test
    void ensureReadyRunsTheWarmupProbesAndReportsReadiness() throws Exception {
        when(bundleRegistry.loadVerified(MODEL)).thenReturn(bundle);
        when(spec.labels()).thenReturn(List.of("label_a"));
        when(spec.warmupProbes()).thenReturn(List.of("probe one"));
        when(runtimeManager.environment()).thenReturn(Optional.of(mock(OrtEnvironment.class)));
        when(sessionLeaseManager.acquire(eq(bundle), any(Instant.class))).thenReturn(lease);
        runTaskInline();

        assertTrue(engine(Duration.ofSeconds(2), Set.of()).ensureReady());

        verify(runner).classify(any(), eq(lease), any(), eq(spec), eq("probe one"), any(Instant.class));
        verify(lease).close();
    }

    @Test
    void ensureReadyReturnsFalseWhenTheWarmupFutureTimesOut() throws Exception {
        when(bundleRegistry.loadVerified(MODEL)).thenReturn(bundle);
        when(spec.labels()).thenReturn(List.of("label_a"));
        when(spec.warmupProbes()).thenReturn(List.of("probe one"));
        neverComplete();

        assertFalse(engine(Duration.ofMillis(50), Set.of()).ensureReady());
    }

    @Test
    void ensureReadyRestoresTheInterruptFlagWhenItsWaitIsInterrupted() throws Exception {
        when(bundleRegistry.loadVerified(MODEL)).thenReturn(bundle);
        when(spec.labels()).thenReturn(List.of("label_a"));
        when(spec.warmupProbes()).thenReturn(List.of("probe one"));
        neverComplete();

        boolean interruptRestored;
        Thread.currentThread().interrupt();
        try {
            assertFalse(engine(Duration.ofSeconds(2), Set.of()).ensureReady());
            interruptRestored = Thread.currentThread().isInterrupted();
        } finally {
            Thread.interrupted();
        }

        assertTrue(interruptRestored, "ensureReady must restore the interrupt status it consumed");
    }

    // ===================== label-map validation =====================

    @Test
    void classifyRejectsALabelMapThatCollapsesTwoLabelsIntoOne() throws Exception {
        acceptPermitAndBundle();
        when(spec.labels()).thenReturn(List.of("label_a", "label_b"));

        // A mapper that is not injective: two validated labels would become the same application value.
        ClassifierResult<String> result =
                engine(Duration.ofSeconds(2), Set.of(), label -> "SAME").classify("hello");

        assertEquals(NoOpinionReason.MISSING_MODEL, reason(result));
        verify(circuitBreaker).recordFailure(permit);
    }

    @Test
    void classifyRejectsALabelSetThatDoesNotMatchTheRequiredContract() throws Exception {
        acceptPermitAndBundle();

        ClassifierResult<String> result =
                engine(Duration.ofSeconds(2), Set.of("OTHER")).classify("hello");

        assertEquals(NoOpinionReason.MISSING_MODEL, reason(result));
        verify(circuitBreaker).recordFailure(permit);
    }

    @Test
    void classifyAcceptsALabelSetThatMatchesTheRequiredContract() throws Exception {
        acceptPermitAndBundle();
        when(runtimeManager.environment()).thenReturn(Optional.of(mock(OrtEnvironment.class)));
        when(sessionLeaseManager.acquire(eq(bundle), any(Instant.class))).thenReturn(lease);
        when(runner.classify(any(), eq(lease), any(), eq(spec), anyString(), any(Instant.class)))
                .thenReturn(new ClassifierResult.Opinion<>("label_a", 0.5f, "rule-1"));
        runTaskInline();

        // label_a maps to LABEL_A, which is exactly the required contract: validation passes.
        ClassifierResult<String> result =
                engine(Duration.ofSeconds(2), Set.of("LABEL_A")).classify("hello");

        assertEquals(new ClassifierResult.Opinion<>("LABEL_A", 0.5f, "rule-1"), result);
    }

    // ===================== ONNX Runtime unavailable =====================

    @Test
    void classifyFailsClosedWhenOnnxRuntimeIsUnavailable() throws Exception {
        acceptPermitAndBundle();
        when(runtimeManager.environment()).thenReturn(Optional.empty());
        runTaskInline();

        ClassifierResult<String> result =
                engine(Duration.ofSeconds(2), Set.of()).classify("hello");

        assertEquals(NoOpinionReason.INFERENCE_ERROR, reason(result));
        verify(circuitBreaker).recordFailure(permit);
    }

    @Test
    void ensureReadyReturnsFalseWhenOnnxRuntimeIsUnavailable() throws Exception {
        when(bundleRegistry.loadVerified(MODEL)).thenReturn(bundle);
        when(spec.labels()).thenReturn(List.of("label_a"));
        when(runtimeManager.environment()).thenReturn(Optional.empty());
        runTaskInline();

        assertFalse(engine(Duration.ofSeconds(2), Set.of()).ensureReady());
    }

    // ===================== warmup short-circuits =====================

    @Test
    void ensureReadyReturnsFalseForABlankModelWithoutTouchingTheRuntime() {
        OnnxShadowClassifierEngine<String> blank = new OnnxShadowClassifierEngine<>(
                bundleRegistry,
                runtimeManager,
                sessionLeaseManager,
                scheduler,
                runner,
                circuitBreaker,
                "   ",
                Duration.ofSeconds(2),
                1024L,
                3,
                String::toUpperCase,
                Set.of());

        assertFalse(blank.ensureReady());
        verifyNoInteractions(bundleRegistry, scheduler);
    }

    @Test
    void ensureReadyShortCircuitsOnceTheModelIsAlreadyWarm() throws Exception {
        when(bundleRegistry.loadVerified(MODEL)).thenReturn(bundle);
        when(spec.labels()).thenReturn(List.of("label_a"));
        when(spec.warmupProbes()).thenReturn(List.of("probe one"));
        when(runtimeManager.environment()).thenReturn(Optional.of(mock(OrtEnvironment.class)));
        when(sessionLeaseManager.acquire(eq(bundle), any(Instant.class))).thenReturn(lease);
        runTaskInline();

        OnnxShadowClassifierEngine<String> ready = engine(Duration.ofSeconds(2), Set.of());
        assertTrue(ready.ensureReady());
        // The second call short-circuits on warmedUp and never re-validates the bundle.
        assertTrue(ready.ensureReady());

        verify(bundleRegistry, times(1)).loadVerified(MODEL);
    }

    @Test
    void classifyWarmsUpOnlyOnceAcrossRepeatedCalls() throws Exception {
        acceptPermitAndBundle();
        when(spec.warmupProbes()).thenReturn(List.of("probe one"));
        when(runtimeManager.environment()).thenReturn(Optional.of(mock(OrtEnvironment.class)));
        when(sessionLeaseManager.acquire(eq(bundle), any(Instant.class))).thenReturn(lease);
        when(runner.classify(any(), eq(lease), any(), eq(spec), anyString(), any(Instant.class)))
                .thenReturn(new ClassifierResult.Opinion<>("label_a", 0.75f, "rule-1"));
        runTaskInline();

        OnnxShadowClassifierEngine<String> warm = engine(Duration.ofSeconds(2), Set.of());
        warm.classify("hello");
        warm.classify("hello");

        // One warmup probe on the first call only, plus one inference per call.
        verify(runner, times(3)).classify(any(), eq(lease), any(), eq(spec), anyString(), any(Instant.class));
    }

    // ===================== remaining failure-translation arms =====================

    @Test
    void classifyTreatsAnUnrecognisedSessionAcquireFailureAsAnInferenceError() throws Exception {
        acceptPermitAndBundle();
        when(scheduler.submit(any(Instant.class), any()))
                .thenReturn(CompletableFuture.failedFuture(new OnnxSessionAcquireException(
                        OnnxSessionAcquireException.Reason.SESSION_LOAD_FAILED, "session load failed")));

        ClassifierResult<String> result =
                engine(Duration.ofSeconds(2), Set.of()).classify("hello");

        // Neither DEADLINE_EXCEEDED nor ADMISSION_REJECTED: the acquire branch falls through.
        assertEquals(NoOpinionReason.INFERENCE_ERROR, reason(result));
        verify(circuitBreaker).recordFailure(permit);
    }

    @Test
    void classifyTreatsANonTimeoutSchedulingCauseAsAnInferenceError() throws Exception {
        acceptPermitAndBundle();
        when(scheduler.submit(any(Instant.class), any()))
                .thenReturn(CompletableFuture.failedFuture(new OnnxInferenceSchedulingException(
                        OnnxInferenceSchedulingException.Reason.SCHEDULER_STOPPED, "scheduler stopped")));

        ClassifierResult<String> result =
                engine(Duration.ofSeconds(2), Set.of()).classify("hello");

        assertEquals(NoOpinionReason.INFERENCE_ERROR, reason(result));
        verify(circuitBreaker).recordFailure(permit);
    }

    @Test
    void constructorRejectsANegativeDuration() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new OnnxShadowClassifierEngine<>(
                        bundleRegistry,
                        runtimeManager,
                        sessionLeaseManager,
                        scheduler,
                        runner,
                        circuitBreaker,
                        MODEL,
                        Duration.ofSeconds(-1),
                        1024L,
                        3,
                        String::toUpperCase,
                        Set.of()));
    }
}
