package com.amsha.localml.onnx;

import ai.onnxruntime.OrtEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import com.amsha.core.classifier.ClassifierResult;
import com.amsha.core.classifier.ClassifierResult.NoOpinionReason;

/** Shared verified-bundle execution, lifecycle, deadline, and breaker path for shadow classifiers. */
final class OnnxShadowClassifierEngine<T> {

    private final OnnxModelBundleRegistry bundleRegistry;
    private final OnnxRuntimeManager runtimeManager;
    private final OnnxSessionLeaseManager sessionLeaseManager;
    private final OnnxInferenceScheduler scheduler;
    private final OnnxTextClassifierRunner runner;
    private final OnnxClassifierCircuitBreaker circuitBreaker;
    private final String model;
    private final Duration timeout;
    private final long maxInputBytes;
    private final int warmupMaxProbes;
    private final Function<String, T> labelMapper;
    private final Set<T> requiredLabels;
    private final Object warmupMonitor = new Object();

    private volatile VerifiedOnnxModelBundle verifiedBundle;
    private volatile Map<String, T> mappedLabels = Map.of();
    private volatile boolean warmedUp;

    OnnxShadowClassifierEngine(
            OnnxModelBundleRegistry bundleRegistry,
            OnnxRuntimeManager runtimeManager,
            OnnxSessionLeaseManager sessionLeaseManager,
            OnnxInferenceScheduler scheduler,
            OnnxTextClassifierRunner runner,
            OnnxClassifierCircuitBreaker circuitBreaker,
            String model,
            Duration timeout,
            long maxInputBytes,
            int warmupMaxProbes,
            Function<String, T> labelMapper,
            Set<T> requiredLabels) {
        this.bundleRegistry = Objects.requireNonNull(bundleRegistry, "bundleRegistry");
        this.runtimeManager = Objects.requireNonNull(runtimeManager, "runtimeManager");
        this.sessionLeaseManager = Objects.requireNonNull(sessionLeaseManager, "sessionLeaseManager");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker, "circuitBreaker");
        this.model = Objects.requireNonNull(model, "model").trim();
        this.timeout = requirePositive(timeout, "timeout");
        this.maxInputBytes = requirePositive(maxInputBytes, "maxInputBytes");
        this.warmupMaxProbes = requirePositive(warmupMaxProbes, "warmupMaxProbes");
        this.labelMapper = Objects.requireNonNull(labelMapper, "labelMapper");
        this.requiredLabels = Set.copyOf(Objects.requireNonNull(requiredLabels, "requiredLabels"));
    }

    ClassifierResult<T> classify(String input) {
        Objects.requireNonNull(input, "input");
        if (model.isBlank()) return noOpinion(NoOpinionReason.MISSING_MODEL);
        if (input.getBytes(StandardCharsets.UTF_8).length > maxInputBytes) {
            return noOpinion(NoOpinionReason.INFERENCE_ERROR);
        }

        var permit = circuitBreaker.tryAcquire();
        if (permit.isEmpty()) return noOpinion(NoOpinionReason.CIRCUIT_OPEN);
        OnnxClassifierCircuitBreaker.Permit granted = permit.orElseThrow();

        VerifiedOnnxModelBundle bundle;
        OnnxClassifierBundleSpec spec;
        try {
            bundle = loadBundle();
            spec = bundle.manifest().classifierSpec().orElseThrow();
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            circuitBreaker.recordFailure(granted);
            return noOpinion(NoOpinionReason.MISSING_MODEL);
        }

        Instant deadline = Instant.now().plus(timeout);
        try {
            var future = scheduler.submit(deadline, () -> run(bundle, spec, input, deadline));
            ClassifierResult<T> result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            circuitBreaker.recordSuccess(granted);
            return result;
        } catch (OnnxInferenceSchedulingException e) {
            if (e.reason() == OnnxInferenceSchedulingException.Reason.OVERLOAD_REJECTED) {
                circuitBreaker.recordNeutral(granted);
                return noOpinion(NoOpinionReason.OVERLOAD_REJECTED);
            }
            circuitBreaker.recordFailure(granted);
            return noOpinion(
                    e.reason() == OnnxInferenceSchedulingException.Reason.TIMEOUT
                            ? NoOpinionReason.TIMEOUT
                            : NoOpinionReason.INFERENCE_ERROR);
        } catch (TimeoutException e) {
            circuitBreaker.recordFailure(granted);
            return noOpinion(NoOpinionReason.TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            circuitBreaker.recordNeutral(granted);
            return noOpinion(NoOpinionReason.INFERENCE_ERROR);
        } catch (ExecutionException e) {
            NoOpinionReason reason = failureReason(e.getCause());
            circuitBreaker.recordFailure(granted);
            return noOpinion(reason);
        }
    }

    private ClassifierResult<T> run(
            VerifiedOnnxModelBundle bundle, OnnxClassifierBundleSpec spec, String input, Instant deadline)
            throws Exception {
        OrtEnvironment environment = runtimeManager
                .environment()
                .orElseThrow(() -> new OnnxClassifierInferenceException("ONNX Runtime is unavailable"));
        try (OnnxSessionLease lease = sessionLeaseManager.acquire(bundle, deadline)) {
            warmUp(environment, lease, spec);
            return mapResult(runner.classify(environment, lease.session(), lease.tokenizer(), spec, input));
        }
    }

    private void warmUp(OrtEnvironment environment, OnnxSessionLease lease, OnnxClassifierBundleSpec spec)
            throws OnnxClassifierInferenceException {
        if (warmedUp) return;
        synchronized (warmupMonitor) {
            if (warmedUp) return;
            for (String probe :
                    spec.warmupProbes().stream().limit(warmupMaxProbes).toList()) {
                runner.classify(environment, lease.session(), lease.tokenizer(), spec, probe);
            }
            warmedUp = true;
        }
    }

    private ClassifierResult<T> mapResult(ClassifierResult<String> result) throws OnnxClassifierInferenceException {
        return switch (result) {
            case ClassifierResult.Opinion<String> opinion -> {
                T mapped = mappedLabels.get(opinion.value());
                if (mapped == null) {
                    throw new OnnxClassifierInferenceException("Classifier output label is absent from validated map");
                }
                yield new ClassifierResult.Opinion<>(mapped, opinion.confidence(), opinion.ruleId());
            }
            case ClassifierResult.NoOpinion<String> noOpinion -> new ClassifierResult.NoOpinion<>(noOpinion.reason());
        };
    }

    private VerifiedOnnxModelBundle loadBundle() throws IOException {
        VerifiedOnnxModelBundle current = verifiedBundle;
        if (current != null) return current;
        synchronized (this) {
            current = verifiedBundle;
            if (current != null) return current;
            current = bundleRegistry.loadVerified(model);
            OnnxClassifierBundleSpec spec = current.manifest()
                    .classifierSpec()
                    .orElseThrow(() -> new IllegalStateException("Bundle has no classifier contract"));
            Map<String, T> labels = new LinkedHashMap<>();
            for (String label : spec.labels()) {
                labels.put(label, Objects.requireNonNull(labelMapper.apply(label), "mapped classifier label"));
            }
            Set<T> distinctLabels = new HashSet<>(labels.values());
            if (distinctLabels.size() != labels.size()) {
                throw new IllegalArgumentException("Classifier labels must map to distinct application values");
            }
            if (!requiredLabels.isEmpty() && !distinctLabels.equals(requiredLabels)) {
                throw new IllegalArgumentException(
                        "Classifier label set does not match the required application contract");
            }
            mappedLabels = Map.copyOf(labels);
            verifiedBundle = current;
            return current;
        }
    }

    private static NoOpinionReason failureReason(Throwable failure) {
        if (failure instanceof OnnxSessionAcquireException acquire
                && acquire.reason() == OnnxSessionAcquireException.Reason.DEADLINE_EXCEEDED) {
            return NoOpinionReason.TIMEOUT;
        }
        if (failure instanceof OnnxInferenceSchedulingException scheduling
                && scheduling.reason() == OnnxInferenceSchedulingException.Reason.TIMEOUT) {
            return NoOpinionReason.TIMEOUT;
        }
        return NoOpinionReason.INFERENCE_ERROR;
    }

    private static <T> ClassifierResult<T> noOpinion(NoOpinionReason reason) {
        return new ClassifierResult.NoOpinion<>(reason);
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static long requirePositive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
