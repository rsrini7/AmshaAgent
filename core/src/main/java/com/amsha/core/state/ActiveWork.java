package com.amsha.core.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The work a session is currently performing: the goal it is pursuing plus the
 * coarse phase it has reached.
 *
 * <p>Goal and phase always travel together (busy message, {@code /status}, the
 * REST status endpoint), so they are modelled as one value instead of a pair of
 * strings that can drift apart at call sites.
 */
public record ActiveWork(String goal, String phase) {

    /** Shared instance for "nothing running / nothing known". */
    public static final ActiveWork EMPTY = new ActiveWork(null, null);

    public static ActiveWork of(String goal, String phase) {
        return new ActiveWork(goal, phase);
    }

    /**
     * Canonical resolver for a session's active work from available in-memory signals
     * and fallback checkpoint goal.
     */
    public static ActiveWork resolve(
            Optional<ActiveWork> liveWork,
            Optional<String> activeGoal,
            Optional<String> activePhase,
            String fallbackGoal) {
        ActiveWork work = (liveWork != null && liveWork.isPresent()) ? liveWork.get() : EMPTY;
        String goal = work.goal();
        if ((goal == null || goal.isBlank()) && activeGoal != null && activeGoal.isPresent()) {
            goal = activeGoal.get();
        }
        if (goal == null || goal.isBlank()) {
            goal = fallbackGoal;
        }
        String phase = work.phase();
        if ((phase == null || phase.isBlank()) && activePhase != null && activePhase.isPresent()) {
            phase = activePhase.get();
        }
        return of(goal, phase);
    }

    /** {@code true} when neither a goal nor a phase is available. */
    public boolean isEmpty() {
        return (goal() == null || goal().isBlank()) && (phase() == null || phase().isBlank());
    }

    /** Copies this work with a different goal, preserving the phase. */
    public ActiveWork withGoal(String newGoal) {
        return new ActiveWork(newGoal, phase());
    }

    /** Human-readable phase, e.g. {@code running read_file}; empty when unknown. */
    public String phaseLabel() {
        return ActivityPhase.label(phase());
    }

    /** Canonical maximum snippet length for rendering goals in status and busy messages. */
    public static final int MAX_GOAL_LENGTH = 60;

    /** Renders the live-work detail using the canonical max goal length (60). */
    public String describe() {
        return describe(MAX_GOAL_LENGTH);
    }

    /**
     * Renders the live-work detail shared by the busy message and {@code /status}:
     * {@code "the goal" · running read_file}. Empty when there is nothing to show.
     *
     * @param maxGoalLength goals longer than this are truncated with an ellipsis
     */
    public String describe(int maxGoalLength) {
        List<String> parts = new ArrayList<>(2);
        String snippet = goalSnippet(maxGoalLength);
        if (snippet != null) {
            parts.add("\"" + snippet + "\"");
        }
        String label = phaseLabel();
        if (!label.isBlank()) {
            parts.add(label);
        }
        return String.join(" · ", parts);
    }

    private String goalSnippet(int maxGoalLength) {
        if (goal() == null || goal().isBlank()) {
            return null;
        }
        String flat = goal().strip().replace("\r", " ").replace("\n", " ");
        if (maxGoalLength <= 0 || flat.length() <= maxGoalLength) {
            return flat;
        }
        int keep = maxGoalLength - 3;
        return keep <= 0 ? "..." : flat.substring(0, keep) + "...";
    }
}
