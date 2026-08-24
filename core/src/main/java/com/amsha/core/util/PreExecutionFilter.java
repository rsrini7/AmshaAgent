package com.amsha.core.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.security.api.AutonomyLevel;

/**
 * Classifies tools in a parallel batch as pre-executable or HITL-pending.
 * §7.31
 */
public final class PreExecutionFilter {

    private PreExecutionFilter() {}

    public record SplitResult(List<ParallelToolCall> preExecutable, List<ParallelToolCall> hitlPending) {}

    /**
     * Splits calls into pre-executable vs HITL-pending.
     * A tool is pre-executable only if its name appears in
     * configuredSafeTools OR autonomyLevel == FULL_AUTONOMY. §7.31
     */
    public static SplitResult split(
            List<ParallelToolCall> calls, AutonomyLevel sessionAutonomy, Set<String> configuredSafeTools) {

        List<ParallelToolCall> pre = new ArrayList<>();
        List<ParallelToolCall> hitl = new ArrayList<>();

        if (calls == null) {
            return new SplitResult(pre, hitl);
        }

        for (ParallelToolCall call : calls) {
            if (sessionAutonomy == AutonomyLevel.FULL || configuredSafeTools.contains(call.toolName())) {
                pre.add(call);
            } else {
                hitl.add(call);
            }
        }
        return new SplitResult(pre, hitl);
    }
}
