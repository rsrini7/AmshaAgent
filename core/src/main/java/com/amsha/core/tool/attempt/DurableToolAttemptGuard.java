package com.amsha.core.tool.attempt;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.amsha.persistence.api.ToolAttemptDto;
import com.amsha.persistence.api.ToolAttemptStatus;
import com.amsha.persistence.api.ToolAttemptStorePort;
import com.amsha.tool.api.IdempotencyMode;

/**
 * Checks durable action locks and replay claims before history/cache deduplication.
 * The store rechecks claims atomically when it admits a new attempt.
 */
@ApplicationScoped
public class DurableToolAttemptGuard {
    private final Instance<ToolAttemptStorePort> stores;
    private final ActionFingerprintGenerator fingerprints;
    private final ToolTargetResolver targetResolver;

    @Inject
    public DurableToolAttemptGuard(
            Instance<ToolAttemptStorePort> stores,
            ActionFingerprintGenerator fingerprints,
            ToolTargetResolver targetResolver) {
        this.stores = stores;
        this.fingerprints = fingerprints;
        this.targetResolver = targetResolver;
    }

    /**
     * Returns a durable denial or an already-settled AT_MOST_ONCE success replay.
     * An empty result means the caller may continue to ordinary history/cache checks.
     */
    public Optional<Decision> check(
            String workspaceId,
            String sessionId,
            String toolName,
            Map<String, Object> parameters,
            IdempotencyMode idempotencyMode) {
        if (stores == null) {
            // The null case is limited to direct, lightweight unit construction.
            return Optional.empty();
        }
        if (stores.isUnsatisfied()) {
            return Optional.of(Decision.blocked("Durable tool attempt storage is unavailable."));
        }
        if (stores.isAmbiguous()) {
            return Optional.of(Decision.blocked("Durable tool attempt storage is ambiguous."));
        }
        if (workspaceId == null || workspaceId.isBlank()) {
            return Optional.of(Decision.blocked("Workspace context is unavailable for tool safety checks."));
        }

        try {
            ToolAttemptStorePort store = stores.get();
            String actionFingerprint = fingerprints.generate(workspaceId, sessionId, toolName, parameters);
            ToolTargetResolution target = targetResolver.resolve(workspaceId, sessionId, toolName, parameters);
            if (!store.findApplicableLocks(workspaceId, sessionId, actionFingerprint, target.targetFingerprint())
                    .isEmpty()) {
                return Optional.of(Decision.blocked("Tool action is blocked by an unresolved prior execution."));
            }

            if (idempotencyMode == IdempotencyMode.AT_MOST_ONCE) {
                Optional<ToolAttemptDto> terminal = store.findAtMostOnceResult(
                        workspaceId,
                        sessionId,
                        toolName,
                        ToolOperationIdentity.CURRENT_FINGERPRINT_VERSION,
                        actionFingerprint);
                if (terminal.isPresent()) {
                    return replayOrBlock(terminal.get());
                }
                if (store.findClaim(
                                workspaceId,
                                sessionId,
                                toolName,
                                ToolOperationIdentity.CURRENT_FINGERPRINT_VERSION,
                                actionFingerprint)
                        .isPresent()) {
                    return Optional.of(
                            Decision.blocked("Tool action already has a durable AT_MOST_ONCE execution claim."));
                }
            }
            return Optional.empty();
        } catch (RuntimeException _) {
            return Optional.of(Decision.blocked("Durable tool safety checks could not be completed."));
        }
    }

    private static Optional<Decision> replayOrBlock(ToolAttemptDto attempt) {
        Objects.requireNonNull(attempt, "attempt must not be null");
        if (attempt.status().isReplayableAtMostOnceOutcome() && attempt.sanitizedObservation() != null) {
            return Optional.of(Decision.replay(attempt));
        }
        return Optional.of(
                Decision.blocked("AT_MOST_ONCE execution already reached a non-replayable terminal outcome."));
    }

    public record Decision(Kind kind, String observation, boolean succeeded, String errorCode) {
        public Decision {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(observation, "observation must not be null");
            if (kind == Kind.BLOCKED && (succeeded || errorCode != null)) {
                throw new IllegalArgumentException("blocked decision cannot represent a stored outcome");
            }
            if (kind == Kind.REPLAY && succeeded == (errorCode != null)) {
                throw new IllegalArgumentException("replay success and errorCode must agree");
            }
        }

        public static Decision blocked(String observation) {
            return new Decision(Kind.BLOCKED, observation, false, null);
        }

        public static Decision replay(ToolAttemptDto attempt) {
            ToolAttemptStatus status = attempt.status();
            boolean success = status == ToolAttemptStatus.SETTLED_SUCCESS;
            String errorCode =
                    switch (status) {
                        case SETTLED_ERROR -> "TOOL_ATTEMPT_ERROR";
                        case TIMED_OUT -> "TOOL_ATTEMPT_TIMED_OUT";
                        default -> null;
                    };
            return new Decision(Kind.REPLAY, attempt.sanitizedObservation(), success, errorCode);
        }

        public enum Kind {
            BLOCKED,
            REPLAY
        }
    }
}
