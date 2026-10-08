package com.amsha.core.classifier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.amsha.classifier.api.DecisionLatencyBudget;
import com.amsha.classifier.api.ProviderCategory;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Watches the decision plane's observed behaviour against a baseline window and alerts once per drift
 * episode.
 *
 * <p>This is the Operations gate's "monitoring with drift alerting" requirement. It exists because a
 * decision provider can keep answering while quietly getting worse: a hosted model is re-deployed, a
 * self-hosted head is re-quantised, or the traffic mix shifts, and the plane keeps emitting confident
 * answers whose distribution no longer resembles the one it was evaluated against. Nothing about a
 * single evaluation reveals that; only the shape of a window of them does.
 *
 * <p><b>What "drift" means here.</b> The first {@code window} observations on a lane are the baseline.
 * Once the baseline is full, the most recent {@code window} observations are the current sample, and
 * drift is the absolute movement of either the mean confidence or the abstention rate between the two,
 * beyond a configured threshold. The comparison is deliberately on two coarse summary statistics rather
 * than a distribution test: the gate needs a stable, explainable alert, not a p-value.
 *
 * <p><b>Bounded by construction.</b> A lane keeps two fixed-size windows, so memory does not grow with
 * traffic; at most {@link #MAX_LANES} lanes are tracked, so a caller-supplied lane string cannot become
 * an unbounded key space; and the alert is latched per lane <em>and per kind</em>, so a sustained drift
 * produces one WARN rather than one per decision, and a later latency breach is not silently suppressed
 * by an earlier drift (or cost-ceiling) alert on the same lane. Nothing here logs, tags, or retains a
 * payload — only a lane name and three bounded numbers.
 *
 * <p><b>It never changes a decision.</b> The monitor observes; it does not abstain, escalate, or block.
 * The only component in this area that can affect an outcome is the cost ceiling, and that lives in
 * {@link DecisionCostGuard}.
 */
@ApplicationScoped
public class DecisionDriftMonitor {

    /** Default observations per baseline/current window. */
    public static final int DEFAULT_WINDOW = 64;

    /** Upper bound on tracked lanes, so a lane string cannot become an unbounded key space. */
    public static final int MAX_LANES = 8;

    /** Default mean-confidence movement that counts as drift. */
    public static final double DEFAULT_CONFIDENCE_THRESHOLD = 0.15;

    /** Default abstention-rate movement that counts as drift. */
    public static final double DEFAULT_ABSTENTION_THRESHOLD = 0.20;

    /**
     * The distinct reasons this monitor emits a WARN. Each kind latches independently per lane, so one
     * kind's alert cannot hide another's: a drift alert and a latency breach on the same lane are two
     * episodes, and both surface.
     */
    public enum AlertKind {
        DRIFT,
        LATENCY,
        COST_CEILING
    }

    private static final Logger log = Logger.getLogger(DecisionDriftMonitor.class);

    private final int window;
    private final double confidenceThreshold;
    private final double abstentionThreshold;

    private final Map<String, LaneWindow> lanes = new ConcurrentHashMap<>();
    private final Set<AlertKey> alerted = ConcurrentHashMap.newKeySet();

    @Inject
    public DecisionDriftMonitor(
            @ConfigProperty(name = "amsha.decision.drift.window", defaultValue = "" + DEFAULT_WINDOW) int window,
            @ConfigProperty(
                            name = "amsha.decision.drift.confidence-threshold",
                            defaultValue = "" + DEFAULT_CONFIDENCE_THRESHOLD)
                    double confidenceThreshold,
            @ConfigProperty(
                            name = "amsha.decision.drift.abstention-threshold",
                            defaultValue = "" + DEFAULT_ABSTENTION_THRESHOLD)
                    double abstentionThreshold) {
        if (window < 2) {
            throw new IllegalArgumentException("drift window must be >= 2, got " + window);
        }
        if (!validThreshold(confidenceThreshold) || !validThreshold(abstentionThreshold)) {
            throw new IllegalArgumentException("drift thresholds must be finite and in [0,1]");
        }
        this.window = window;
        this.confidenceThreshold = confidenceThreshold;
        this.abstentionThreshold = abstentionThreshold;
    }

    /**
     * Records one observation and emits at most one WARN for the lane.
     *
     * @param lane the provider lane; null or blank is ignored
     * @param opinion whether the engine reached an opinion
     * @param confidence the answer confidence, when reported
     * @param latency the measured latency, when available
     */
    public void observe(String lane, boolean opinion, Double confidence, Duration latency) {
        LaneWindow laneWindow = windowFor(lane);
        if (laneWindow == null) {
            return;
        }
        laneWindow.observe(opinion, confidence, latency);
        if (drifted(lane)) {
            alertOnce(lane, AlertKind.DRIFT, driftMessage(lane, laneWindow));
        } else {
            clearAlert(lane, AlertKind.DRIFT);
        }
    }

    /**
     * Alerts once when a lane's observed p95 latency reaches its budget.
     *
     * @param lane the provider lane
     * @param category the provider category, which selects the budget
     * @param budget the per-lane p95 budget
     * @return whether the lane is currently in breach
     */
    public boolean checkLatency(String lane, ProviderCategory category, DecisionLatencyBudget budget) {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(budget, "budget");
        LaneWindow laneWindow = lane == null ? null : lanes.get(lane);
        if (laneWindow == null) {
            clearAlert(lane, AlertKind.LATENCY);
            return false;
        }
        long p95 = laneWindow.p95LatencyNanos();
        if (p95 <= 0) {
            clearAlert(lane, AlertKind.LATENCY);
            return false;
        }
        long budgetNanos = budget.forCategory(category).toNanos();
        if (p95 < budgetNanos) {
            clearAlert(lane, AlertKind.LATENCY);
            return false;
        }
        alertOnce(
                lane,
                AlertKind.LATENCY,
                "Decision latency breach on lane " + lane + ": observed p95 " + (p95 / 1_000_000) + "ms is at or above "
                        + (budgetNanos / 1_000_000) + "ms for category " + category);
        return true;
    }

    /**
     * Alerts once when a lane is fail-closed because its rolling cost exceeded the approved ceiling.
     *
     * @param lane the provider lane
     * @param ceilingPerThousand the approved ceiling that was exceeded, or null when none is declared
     */
    public void alertCostCeiling(String lane, BigDecimal ceilingPerThousand) {
        if (lane == null) {
            return;
        }
        alertOnce(
                lane,
                AlertKind.COST_CEILING,
                "Decision cost ceiling exceeded on lane " + lane + ": evaluation fails closed"
                        + (ceilingPerThousand == null
                                ? ""
                                : " (approved ceiling " + ceilingPerThousand + " per 1,000)"));
    }

    /** Clears one alert latch after recovery so a later episode can alert again. */
    public void clearAlert(String lane, AlertKind kind) {
        Objects.requireNonNull(kind, "kind");
        if (lane != null) {
            alerted.remove(new AlertKey(lane, kind));
        }
    }

    /**
     * Whether the lane's current window has drifted from its baseline.
     *
     * <p>False until both windows are full: a comparison against a partial baseline would alert on the
     * first few decisions, which is noise, not drift.
     */
    public boolean drifted(String lane) {
        LaneWindow laneWindow = lane == null ? null : lanes.get(lane);
        return laneWindow != null && laneWindow.drifted(confidenceThreshold, abstentionThreshold);
    }

    /** The mean-confidence movement between baseline and current, or 0 when not yet comparable. */
    public double confidenceDelta(String lane) {
        LaneWindow laneWindow = lane == null ? null : lanes.get(lane);
        return laneWindow == null ? 0.0 : laneWindow.confidenceDelta();
    }

    /** The abstention-rate movement between baseline and current, or 0 when not yet comparable. */
    public double abstentionDelta(String lane) {
        LaneWindow laneWindow = lane == null ? null : lanes.get(lane);
        return laneWindow == null ? 0.0 : laneWindow.abstentionDelta();
    }

    /** The observed p95 latency in nanos for a lane's current window, or 0 when unmeasured. */
    public long p95LatencyNanos(String lane) {
        LaneWindow laneWindow = lane == null ? null : lanes.get(lane);
        return laneWindow == null ? 0L : laneWindow.p95LatencyNanos();
    }

    /** Whether any alert has already been emitted for the lane since the last reset. */
    public boolean alertEmitted(String lane) {
        return lane != null && alerted.stream().anyMatch(key -> key.lane().equals(lane));
    }

    /** Whether an alert of the given kind has already been emitted for the lane since the last reset. */
    public boolean alertEmitted(String lane, AlertKind kind) {
        Objects.requireNonNull(kind, "kind");
        return lane != null && alerted.contains(new AlertKey(lane, kind));
    }

    /** How many lanes are currently tracked; bounded by {@link #MAX_LANES}. */
    public int trackedLaneCount() {
        return lanes.size();
    }

    /** Clears a lane's windows and its alert latches, so a fresh episode can be observed. */
    public void reset(String lane) {
        if (lane == null) {
            return;
        }
        lanes.remove(lane);
        alerted.removeIf(key -> key.lane().equals(lane));
    }

    /** Clears every lane's windows and alert latches. */
    public void resetAll() {
        lanes.clear();
        alerted.clear();
    }

    private LaneWindow windowFor(String lane) {
        if (lane == null || lane.isBlank()) {
            return null;
        }
        LaneWindow existing = lanes.get(lane);
        if (existing != null) {
            return existing;
        }
        // Bound the key space: a lane is derived from the selected provider's category today, but the
        // monitor must not grow without limit if that ever widens.
        if (lanes.size() >= MAX_LANES) {
            return null;
        }
        return lanes.computeIfAbsent(lane, _ -> new LaneWindow(window));
    }

    private void alertOnce(String lane, AlertKind kind, String message) {
        if (alerted.add(new AlertKey(lane, kind))) {
            log.warn(message);
        }
    }

    /** The identity of a latched alert: one lane and one kind, so kinds never suppress each other. */
    private record AlertKey(String lane, AlertKind kind) {}

    private static String driftMessage(String lane, LaneWindow laneWindow) {
        return "Decision drift detected on lane " + lane + ": confidence mean moved "
                + format(laneWindow.confidenceDelta()) + " (baseline " + format(laneWindow.baselineConfidenceMean())
                + ", current " + format(laneWindow.currentConfidenceMean()) + "); abstention rate moved "
                + format(laneWindow.abstentionDelta()) + " (baseline " + format(laneWindow.baselineAbstentionRate())
                + ", current " + format(laneWindow.currentAbstentionRate()) + ")";
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static boolean validThreshold(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }

    /**
     * Two fixed-size windows for one lane: the first {@code capacity} observations, and the most recent
     * {@code capacity}.
     *
     * <p>Confidence is tracked only for opinion observations; an abstention carries no confidence, and
     * folding a synthetic value in would move the mean for a reason that is not a confidence change.
     * Abstention is tracked over every observation in the window.
     */
    private static final class LaneWindow {

        private final int capacity;
        private final Deque<Observation> baseline = new ArrayDeque<>();
        private final Deque<Observation> current = new ArrayDeque<>();

        private LaneWindow(int capacity) {
            this.capacity = capacity;
        }

        private synchronized void observe(boolean opinion, Double confidence, Duration latency) {
            Observation observation = new Observation(
                    opinion,
                    opinion ? confidence : null,
                    latency == null || latency.isNegative() ? null : latency.toNanos());
            if (baseline.size() < capacity) {
                baseline.addLast(observation);
                return;
            }
            current.addLast(observation);
            while (current.size() > capacity) {
                current.removeFirst();
            }
        }

        private synchronized boolean drifted(double confidenceThreshold, double abstentionThreshold) {
            return comparable() && (confidenceDelta() > confidenceThreshold || abstentionDelta() > abstentionThreshold);
        }

        private synchronized boolean comparable() {
            return baseline.size() >= capacity && current.size() >= capacity;
        }

        private synchronized double confidenceDelta() {
            if (!comparable()) {
                return 0.0;
            }
            return Math.abs(currentConfidenceMean() - baselineConfidenceMean());
        }

        private synchronized double abstentionDelta() {
            if (!comparable()) {
                return 0.0;
            }
            return Math.abs(currentAbstentionRate() - baselineAbstentionRate());
        }

        private synchronized double baselineConfidenceMean() {
            return meanConfidence(baseline);
        }

        private synchronized double currentConfidenceMean() {
            return meanConfidence(current);
        }

        private synchronized double baselineAbstentionRate() {
            return abstentionRate(baseline);
        }

        private synchronized double currentAbstentionRate() {
            return abstentionRate(current);
        }

        private synchronized long p95LatencyNanos() {
            List<Long> measured = new ArrayList<>();
            for (Observation observation : current) {
                if (observation.latencyNanos() != null) {
                    measured.add(observation.latencyNanos());
                }
            }
            if (measured.isEmpty()) {
                return 0L;
            }
            measured.sort(Long::compareTo);
            int n = measured.size();
            int index = (int) Math.ceil(0.95 * n) - 1;
            if (index < 0) {
                index = 0;
            }
            if (index > n - 1) {
                index = n - 1;
            }
            return measured.get(index);
        }

        private static double meanConfidence(Deque<Observation> observations) {
            double total = 0.0;
            int count = 0;
            for (Observation observation : observations) {
                if (observation.confidence() != null) {
                    total += observation.confidence();
                    count++;
                }
            }
            return count == 0 ? 0.0 : total / count;
        }

        private static double abstentionRate(Deque<Observation> observations) {
            if (observations.isEmpty()) {
                return 0.0;
            }
            int abstentions = 0;
            for (Observation observation : observations) {
                if (!observation.opinion()) {
                    abstentions++;
                }
            }
            return (double) abstentions / observations.size();
        }

        private record Observation(boolean opinion, Double confidence, Long latencyNanos) {}
    }
}
