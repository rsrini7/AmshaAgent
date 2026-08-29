package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.amsha.classifier.api.ClassifierResult;
import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OnnxShadowClassifierEngineTest {

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

    OnnxShadowClassifierEngine<String> engine;

    @BeforeEach
    void setUp() {
        engine = new OnnxShadowClassifierEngine<>(
                bundleRegistry,
                runtimeManager,
                sessionLeaseManager,
                scheduler,
                runner,
                circuitBreaker,
                "test-model",
                Duration.ofSeconds(2),
                1024L,
                3,
                String::toUpperCase,
                Set.of());
    }

    @Test
    @DisplayName("Constructor parameter validation rejects non-positive or null parameters")
    void constructor_parameterValidation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new OnnxShadowClassifierEngine<>(
                        bundleRegistry,
                        runtimeManager,
                        sessionLeaseManager,
                        scheduler,
                        runner,
                        circuitBreaker,
                        "model",
                        Duration.ZERO,
                        1024L,
                        3,
                        String::toUpperCase,
                        Set.of()));

        assertThrows(
                IllegalArgumentException.class,
                () -> new OnnxShadowClassifierEngine<>(
                        bundleRegistry,
                        runtimeManager,
                        sessionLeaseManager,
                        scheduler,
                        runner,
                        circuitBreaker,
                        "model",
                        Duration.ofSeconds(1),
                        0L,
                        3,
                        String::toUpperCase,
                        Set.of()));

        assertThrows(
                IllegalArgumentException.class,
                () -> new OnnxShadowClassifierEngine<>(
                        bundleRegistry,
                        runtimeManager,
                        sessionLeaseManager,
                        scheduler,
                        runner,
                        circuitBreaker,
                        "model",
                        Duration.ofSeconds(1),
                        1024L,
                        0,
                        String::toUpperCase,
                        Set.of()));
    }

    @Test
    @DisplayName("classify rejects null input and handles blank model")
    void classify_nullInputOrBlankModel() {
        assertThrows(NullPointerException.class, () -> engine.classify(null));

        OnnxShadowClassifierEngine<String> blankEngine = new OnnxShadowClassifierEngine<>(
                bundleRegistry,
                runtimeManager,
                sessionLeaseManager,
                scheduler,
                runner,
                circuitBreaker,
                "  ",
                Duration.ofSeconds(1),
                1024L,
                3,
                String::toUpperCase,
                Set.of());

        ClassifierResult<String> blankResult = blankEngine.classify("input text");
        assertInstanceOf(ClassifierResult.NoOpinion.class, blankResult);
        assertEquals(NoOpinionReason.MISSING_MODEL, ((ClassifierResult.NoOpinion<String>) blankResult).reason());
    }

    @Test
    @DisplayName("classify returns INFERENCE_ERROR when input exceeds maxInputBytes")
    void classify_oversizedInput_returnsInferenceError() {
        String largeInput = "A".repeat(2048);
        ClassifierResult<String> result = engine.classify(largeInput);
        assertInstanceOf(ClassifierResult.NoOpinion.class, result);
        assertEquals(NoOpinionReason.INFERENCE_ERROR, ((ClassifierResult.NoOpinion<String>) result).reason());
    }

    @Test
    @DisplayName("classify returns CIRCUIT_OPEN when circuit breaker rejects permit")
    void classify_circuitOpen_returnsCircuitOpen() {
        when(circuitBreaker.tryAcquire()).thenReturn(Optional.empty());

        ClassifierResult<String> result = engine.classify("hello");
        assertInstanceOf(ClassifierResult.NoOpinion.class, result);
        assertEquals(NoOpinionReason.CIRCUIT_OPEN, ((ClassifierResult.NoOpinion<String>) result).reason());
    }

    @Test
    @DisplayName("classify returns MISSING_MODEL when bundle loading throws IOException")
    void classify_bundleLoadFailure_returnsMissingModel() throws IOException {
        OnnxClassifierCircuitBreaker.Permit permit = mock(OnnxClassifierCircuitBreaker.Permit.class);
        when(circuitBreaker.tryAcquire()).thenReturn(Optional.of(permit));
        when(bundleRegistry.loadVerified("test-model")).thenThrow(new IOException("Bundle missing"));

        ClassifierResult<String> result = engine.classify("hello");
        assertInstanceOf(ClassifierResult.NoOpinion.class, result);
        assertEquals(NoOpinionReason.MISSING_MODEL, ((ClassifierResult.NoOpinion<String>) result).reason());
        verify(circuitBreaker).recordFailure(permit);
    }

    @Test
    @DisplayName("classify handles scheduler overload and timeout rejections")
    void classify_schedulerExceptions() throws Exception {
        OnnxClassifierCircuitBreaker.Permit permit = mock(OnnxClassifierCircuitBreaker.Permit.class);
        when(circuitBreaker.tryAcquire()).thenReturn(Optional.of(permit));

        VerifiedOnnxModelBundle bundle = mock(VerifiedOnnxModelBundle.class);
        OnnxModelBundleManifest manifest = mock(OnnxModelBundleManifest.class);
        OnnxClassifierBundleSpec spec = mock(OnnxClassifierBundleSpec.class);
        when(spec.labels()).thenReturn(List.of("label_a"));
        when(manifest.classifierSpec()).thenReturn(Optional.of(spec));
        when(bundle.manifest()).thenReturn(manifest);
        when(bundleRegistry.loadVerified("test-model")).thenReturn(bundle);

        // Overload rejected
        when(scheduler.submit(any(Instant.class), any()))
                .thenThrow(new OnnxInferenceSchedulingException(
                        OnnxInferenceSchedulingException.Reason.OVERLOAD_REJECTED, "overload"));

        ClassifierResult<String> overloadResult = engine.classify("hello");
        assertInstanceOf(ClassifierResult.NoOpinion.class, overloadResult);
        assertEquals(NoOpinionReason.OVERLOAD_REJECTED, ((ClassifierResult.NoOpinion<String>) overloadResult).reason());
        verify(circuitBreaker).recordNeutral(permit);

        // Scheduling timeout
        when(scheduler.submit(any(Instant.class), any()))
                .thenThrow(new OnnxInferenceSchedulingException(
                        OnnxInferenceSchedulingException.Reason.TIMEOUT, "timed out"));

        ClassifierResult<String> timeoutResult = engine.classify("hello");
        assertInstanceOf(ClassifierResult.NoOpinion.class, timeoutResult);
        assertEquals(NoOpinionReason.TIMEOUT, ((ClassifierResult.NoOpinion<String>) timeoutResult).reason());
    }

    @Test
    @DisplayName("ensureReady returns false when bundle is missing or fails warmup")
    void ensureReady_failsGracefully() throws IOException {
        when(bundleRegistry.loadVerified("test-model")).thenThrow(new IOException("Bundle missing"));
        assertFalse(engine.ensureReady());
    }

    @Test
    @DisplayName("classify handles manifest without classifier contract and label validation failures")
    void classify_manifestContractViolations() throws IOException {
        OnnxClassifierCircuitBreaker.Permit permit = mock(OnnxClassifierCircuitBreaker.Permit.class);
        when(circuitBreaker.tryAcquire()).thenReturn(Optional.of(permit));

        VerifiedOnnxModelBundle bundleNoSpec = mock(VerifiedOnnxModelBundle.class);
        OnnxModelBundleManifest manifestNoSpec = mock(OnnxModelBundleManifest.class);
        when(manifestNoSpec.classifierSpec()).thenReturn(Optional.empty());
        when(bundleNoSpec.manifest()).thenReturn(manifestNoSpec);
        when(bundleRegistry.loadVerified("test-model")).thenReturn(bundleNoSpec);

        ClassifierResult<String> result = engine.classify("hello");
        assertInstanceOf(ClassifierResult.NoOpinion.class, result);
        assertEquals(NoOpinionReason.MISSING_MODEL, ((ClassifierResult.NoOpinion<String>) result).reason());
    }

    @Test
    @DisplayName("classify handles session acquire deadline exceeded and admission rejected")
    void classify_sessionAcquireExceptions() throws Exception {
        OnnxClassifierCircuitBreaker.Permit permit = mock(OnnxClassifierCircuitBreaker.Permit.class);
        when(circuitBreaker.tryAcquire()).thenReturn(Optional.of(permit));

        VerifiedOnnxModelBundle bundle = mock(VerifiedOnnxModelBundle.class);
        OnnxModelBundleManifest manifest = mock(OnnxModelBundleManifest.class);
        OnnxClassifierBundleSpec spec = mock(OnnxClassifierBundleSpec.class);
        when(spec.labels()).thenReturn(List.of("label_a"));
        when(manifest.classifierSpec()).thenReturn(Optional.of(spec));
        when(bundle.manifest()).thenReturn(manifest);
        when(bundleRegistry.loadVerified("test-model")).thenReturn(bundle);

        when(scheduler.submit(any(Instant.class), any()))
                .thenReturn(CompletableFuture.failedFuture(new OnnxSessionAcquireException(
                        OnnxSessionAcquireException.Reason.DEADLINE_EXCEEDED, "deadline exceeded")));

        ClassifierResult<String> timeoutResult = engine.classify("hello");
        assertInstanceOf(ClassifierResult.NoOpinion.class, timeoutResult);
        assertEquals(NoOpinionReason.TIMEOUT, ((ClassifierResult.NoOpinion<String>) timeoutResult).reason());

        when(scheduler.submit(any(Instant.class), any()))
                .thenReturn(CompletableFuture.failedFuture(new OnnxSessionAcquireException(
                        OnnxSessionAcquireException.Reason.ADMISSION_REJECTED, "admission rejected")));

        ClassifierResult<String> overloadResult = engine.classify("hello");
        assertInstanceOf(ClassifierResult.NoOpinion.class, overloadResult);
        assertEquals(NoOpinionReason.OVERLOAD_REJECTED, ((ClassifierResult.NoOpinion<String>) overloadResult).reason());
    }
}
