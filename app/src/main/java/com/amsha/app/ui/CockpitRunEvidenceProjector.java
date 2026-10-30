package com.amsha.app.ui;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

import com.amsha.app.ui.dto.RunDecisionEvidenceDto;
import com.amsha.app.ui.dto.RunEvidenceContract;
import com.amsha.app.ui.dto.RunEvidenceDto;
import com.amsha.core.agent.trace.TraceStage;
import com.amsha.persistence.api.ExecutionTraceDto;

/** Projects bounded durable trace rows into an allowlisted operator evidence contract. */
final class CockpitRunEvidenceProjector {
    static final int MAX_EVENTS = CockpitResource.MAX_LIMIT;
    private static final int MAX_DECISIONS = RunEvidenceContract.MAX_DECISIONS;

    private CockpitRunEvidenceProjector() {}

    static RunEvidenceDto project(String runId, String sessionId, long eventCount, List<ExecutionTraceDto> traces) {
        List<ExecutionTraceDto> correlated = traces.stream()
                .filter(trace -> runId.equals(trace.runId()) && sessionId.equals(trace.sessionId()))
                .sorted(Comparator.comparingLong(ExecutionTraceDto::sequence))
                .toList();
        boolean eventsTruncated = eventCount > MAX_EVENTS || correlated.size() != eventCount;
        boolean complete = !eventsTruncated;
        List<ExecutionTraceDto> guardEvents = select(correlated, TraceStage.GUARD_CHECK.wireValue());
        List<ExecutionTraceDto> approvalEvents = correlated.stream()
                .filter(trace -> TraceStage.HITL_REQUESTED.wireValue().equals(trace.stage())
                        || TraceStage.HITL_RESOLVED.wireValue().equals(trace.stage()))
                .toList();
        boolean decisionTruncated =
                eventsTruncated || guardEvents.size() > MAX_DECISIONS || approvalEvents.size() > MAX_DECISIONS;

        ExecutionTraceDto start = first(correlated, TraceStage.TURN_START.wireValue());
        ExecutionTraceDto end = last(correlated, TraceStage.TURN_END.wireValue());
        boolean outcomeObserved = complete && end != null;
        String outcome = outcomeObserved ? safeOutcome(end.status()) : RunEvidenceContract.UNAVAILABLE;
        Long latency = complete ? latencyMillis(start, end) : null;
        boolean usageObserved = hasTokenUsage(correlated);
        TokenTotals tokenTotals = tokenTotals(correlated, complete);
        String usageAvailability = usageAvailability(usageObserved, complete, tokenTotals.invalid());
        String costStatus = usageObserved ? RunEvidenceContract.UNPRICED : RunEvidenceContract.UNAVAILABLE;

        return new RunEvidenceDto(
                runId,
                outcome,
                outcomeObserved ? RunEvidenceContract.OBSERVED : RunEvidenceContract.UNAVAILABLE,
                latency,
                latency == null ? RunEvidenceContract.UNAVAILABLE : RunEvidenceContract.MEASURED,
                eventCount,
                eventsTruncated,
                mapDecisions(guardEvents, true),
                mapDecisions(approvalEvents, false),
                decisionTruncated,
                tokenTotals.promptTokens(),
                tokenTotals.completionTokens(),
                usageAvailability,
                costStatus,
                null,
                null,
                usageObserved
                        ? "No run-scoped provider price metadata is stored."
                        : "Run-scoped token usage is unavailable.");
    }

    static RunEvidenceDto unavailable(String runId, long eventCount) {
        return new RunEvidenceDto(
                runId,
                RunEvidenceContract.UNAVAILABLE,
                RunEvidenceContract.UNAVAILABLE,
                null,
                RunEvidenceContract.UNAVAILABLE,
                eventCount,
                eventCount > MAX_EVENTS,
                List.of(),
                List.of(),
                eventCount > MAX_EVENTS,
                null,
                null,
                RunEvidenceContract.UNAVAILABLE,
                RunEvidenceContract.UNAVAILABLE,
                null,
                null,
                "No correlated run trace evidence is available.");
    }

    private static List<ExecutionTraceDto> select(List<ExecutionTraceDto> traces, String stage) {
        return traces.stream().filter(trace -> stage.equals(trace.stage())).toList();
    }

    private static ExecutionTraceDto first(List<ExecutionTraceDto> traces, String stage) {
        return traces.stream()
                .filter(trace -> stage.equals(trace.stage()))
                .findFirst()
                .orElse(null);
    }

    private static ExecutionTraceDto last(List<ExecutionTraceDto> traces, String stage) {
        return traces.stream()
                .filter(trace -> stage.equals(trace.stage()))
                .reduce((left, right) -> right)
                .orElse(null);
    }

    private static Long latencyMillis(ExecutionTraceDto start, ExecutionTraceDto end) {
        if (start == null || end == null || end.timestamp().isBefore(start.timestamp())) {
            return null;
        }
        try {
            return Duration.between(start.timestamp(), end.timestamp()).toMillis();
        } catch (ArithmeticException _) {
            return null;
        }
    }

    private static boolean hasTokenUsage(List<ExecutionTraceDto> traces) {
        return traces.stream()
                .anyMatch(trace -> nonNegative(trace.promptTokens()) || nonNegative(trace.completionTokens()));
    }

    private static String usageAvailability(boolean observed, boolean complete, boolean invalidMetrics) {
        if (!observed) {
            return RunEvidenceContract.UNAVAILABLE;
        }
        return complete && !invalidMetrics ? RunEvidenceContract.OBSERVED : RunEvidenceContract.PARTIAL;
    }

    private static TokenTotals tokenTotals(List<ExecutionTraceDto> traces, boolean complete) {
        boolean invalid =
                traces.stream().anyMatch(trace -> negative(trace.promptTokens()) || negative(trace.completionTokens()));
        if (!complete || invalid) {
            return new TokenTotals(null, null, invalid);
        }
        long prompt = 0;
        long completion = 0;
        boolean promptObserved = false;
        boolean completionObserved = false;
        for (ExecutionTraceDto trace : traces) {
            if (trace.promptTokens() != null && trace.promptTokens() >= 0) {
                prompt += trace.promptTokens();
                promptObserved = true;
            }
            if (trace.completionTokens() != null && trace.completionTokens() >= 0) {
                completion += trace.completionTokens();
                completionObserved = true;
            }
        }
        return new TokenTotals(promptObserved ? prompt : null, completionObserved ? completion : null, false);
    }

    private static boolean nonNegative(Integer value) {
        return value != null && value >= 0;
    }

    private static boolean negative(Integer value) {
        return value != null && value < 0;
    }

    private static List<RunDecisionEvidenceDto> mapDecisions(List<ExecutionTraceDto> traces, boolean guard) {
        return traces.stream()
                .limit(MAX_DECISIONS)
                .map(trace -> new RunDecisionEvidenceDto(
                        safeDecision(trace.status(), guard), safeRisk(trace.riskLevel()), trace.timestamp()))
                .toList();
    }

    private static String safeDecision(String value, boolean guard) {
        if (guard) {
            return switch (value == null ? "" : value) {
                case RunEvidenceContract.ALLOW, RunEvidenceContract.HITL_REQUIRED, RunEvidenceContract.DENIED -> value;
                default -> RunEvidenceContract.UNKNOWN;
            };
        }
        return switch (value == null ? "" : value) {
            case RunEvidenceContract.HITL_REQUIRED,
                    RunEvidenceContract.AWAITING_APPROVAL,
                    RunEvidenceContract.APPROVED,
                    RunEvidenceContract.DENIED,
                    RunEvidenceContract.RESUMED -> value;
            default -> RunEvidenceContract.UNKNOWN;
        };
    }

    private static String safeRisk(String value) {
        return RunEvidenceContract.isRiskLevel(value) ? value : null;
    }

    private static String safeOutcome(String value) {
        return RunEvidenceContract.END.equals(value) ? value : RunEvidenceContract.UNKNOWN;
    }

    private record TokenTotals(Long promptTokens, Long completionTokens, boolean invalid) {}
}
