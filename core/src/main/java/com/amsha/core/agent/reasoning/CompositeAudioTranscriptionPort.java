package com.amsha.core.agent.reasoning;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.amsha.common.util.LogUtil;
import com.amsha.llm.api.audio.AudioTranscriptionPort;
import com.amsha.llm.api.audio.AudioTranscriptionRequest;
import com.amsha.llm.api.audio.AudioTranscriptionResult;

import org.jboss.logging.Logger;

/**
 * Composite {@link AudioTranscriptionPort} that dispatches to registered delegates in descending
 * priority order with fault isolation and configurable fallback cascading.
 * <p>
 * <b>Routing Contract</b>:
 * <ul>
 *   <li>Priority cascading: Iterates delegates ordered by {@link AudioTranscriptionPort#priority()}
 *       descending, tie-broken deterministically by class name.</li>
 *   <li>Graceful delegation: Delegates that are disabled or unavailable must return {@code false} from
 *       {@link #supports(String, long)} or return {@code UNSUPPORTED} from {@link #transcribe} to permit
 *       cascading to lower-priority delegates.</li>
 *   <li>Failure isolation and privacy guard: When a supporting delegate returns {@code FAILED},
 *       {@code TIMEOUT}, or throws a runtime exception during transcription, the composite halts
 *       cascading and returns that failure immediately by default. This zero-egress safeguard
 *       prevents confidential audio from leaking to external cloud providers when a local engine
 *       fails or times out. Cascading on failure only occurs if {@code allowFallbackOnFailure} is
 *       explicitly set to {@code true}.</li>
 *   <li>Terminal results: Successful transcription ({@code TRANSCRIBED}) and payload size limit
 *       rejections ({@code TOO_LARGE}) return immediately without trying subsequent delegates.</li>
 * </ul>
 * <p>
 * <b>Architecture Invariant</b>: This class is intentionally <i>not</i> a CDI bean and must never acquire
 * a bean-defining annotation (such as {@code @ApplicationScoped}) to prevent circular self-injection
 * during provider resolution.
 */
public final class CompositeAudioTranscriptionPort implements AudioTranscriptionPort {

    private static final Logger log = Logger.getLogger(CompositeAudioTranscriptionPort.class);

    private final List<AudioTranscriptionPort> delegates;
    private final boolean allowFallbackOnFailure;

    public CompositeAudioTranscriptionPort(List<AudioTranscriptionPort> delegates) {
        this(delegates, false);
    }

    public CompositeAudioTranscriptionPort(List<AudioTranscriptionPort> delegates, boolean allowFallbackOnFailure) {
        Objects.requireNonNull(delegates, "delegates must not be null");
        this.delegates = delegates.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(AudioTranscriptionPort::priority)
                        .reversed()
                        .thenComparing(d -> d.getClass().getName()))
                .toList();
        this.allowFallbackOnFailure = allowFallbackOnFailure;
    }

    public boolean allowFallbackOnFailure() {
        return allowFallbackOnFailure;
    }

    @Override
    public boolean supports(String mimeType, long declaredBytes) {
        for (AudioTranscriptionPort delegate : delegates) {
            try {
                if (delegate.supports(mimeType, declaredBytes)) {
                    return true;
                }
            } catch (RuntimeException e) {
                log.debugf(
                        "Delegate %s failed supports check: %s",
                        delegate.getClass().getSimpleName(), LogUtil.rootMessage(e));
            }
        }
        return false;
    }

    @Override
    public AudioTranscriptionResult transcribe(AudioTranscriptionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        long started = System.nanoTime();
        AudioTranscriptionResult lastFailure = null;
        boolean anySupporting = false;

        for (AudioTranscriptionPort delegate : delegates) {
            boolean supports;
            try {
                supports = delegate.supports(request.mimeType(), request.declaredBytes());
            } catch (RuntimeException e) {
                log.debugf(
                        "Delegate %s failed supports check during transcribe: %s",
                        delegate.getClass().getSimpleName(), LogUtil.rootMessage(e));
                continue;
            }

            if (!supports) {
                continue;
            }
            anySupporting = true;

            try {
                AudioTranscriptionResult result = delegate.transcribe(request);
                if (result.status() == AudioTranscriptionResult.Status.TRANSCRIBED
                        || result.status() == AudioTranscriptionResult.Status.TOO_LARGE) {
                    return result;
                }
                if (result.status() == AudioTranscriptionResult.Status.UNSUPPORTED) {
                    log.debugf(
                            "Delegate %s returned UNSUPPORTED (%s); cascading to subsequent providers",
                            delegate.getClass().getSimpleName(), result.failureReason());
                    lastFailure = result;
                    continue;
                }
                if (!allowFallbackOnFailure) {
                    log.infof(
                            "Delegate %s returned %s (%s); fallback cascading disallowed to prevent audio data egress",
                            delegate.getClass().getSimpleName(), result.status(), result.failureReason());
                    return result;
                }
                log.debugf(
                        "Delegate %s returned status %s (%s); cascading to subsequent providers",
                        delegate.getClass().getSimpleName(), result.status(), result.failureReason());
                lastFailure = result;
            } catch (RuntimeException e) {
                log.warnf(
                        "Delegate %s threw during transcription: %s",
                        delegate.getClass().getSimpleName(), LogUtil.rootMessage(e));
                AudioTranscriptionResult failure = AudioTranscriptionResult.failure(
                        AudioTranscriptionResult.Status.FAILED,
                        "delegate_threw",
                        Duration.ofNanos(System.nanoTime() - started));
                if (!allowFallbackOnFailure) {
                    return failure;
                }
                lastFailure = failure;
            }
        }

        if (lastFailure != null) {
            return lastFailure;
        }
        String reason = anySupporting ? "all_transcription_providers_failed" : "no_supporting_transcription_provider";
        return AudioTranscriptionResult.failure(
                AudioTranscriptionResult.Status.UNSUPPORTED, reason, Duration.ofNanos(System.nanoTime() - started));
    }

    @Override
    public int priority() {
        return delegates.isEmpty() ? 0 : delegates.getFirst().priority();
    }
}
