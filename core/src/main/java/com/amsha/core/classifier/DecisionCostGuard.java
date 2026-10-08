package com.amsha.core.classifier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Tracks a rolling cost per 1,000 decisions per lane and answers whether an approved ceiling has been
 * exceeded.
 *
 * <p>It exists because the evaluation path is the only place Amsha spends money on a decision, and a
 * provider that is mis-priced, mis-configured, or simply asked more often than expected can spend a lot
 * of it quietly. The gate the ADR asks for is a <em>ceiling</em>, so this type answers a yes/no question
 * about a rolling average and lets the caller fail closed; it does not itself abstain, escalate, or
 * block, and it holds no reference to the evaluation path.
 *
 * <p><b>Why a rolling window rather than a running total.</b> A cumulative total would eventually
 * exceed any ceiling no matter how cheap each decision became, which would turn a cost guard into a
 * shutdown timer. The measure is therefore the mean cost of the last {@code window} <em>priced</em>
 * decisions, expressed per 1,000, and it is only assessed once the window is full — a ceiling is a
 * property of a rate, and a rate needs a denominator. Unpriced decisions are not recorded at all: an
 * absent rate must never be read as a zero cost.
 *
 * <p><b>Bounded.</b> A lane keeps one fixed-size window, at most {@link #MAX_LANES} lanes are tracked,
 * and nothing here logs or retains a payload.
 */
@ApplicationScoped
public class DecisionCostGuard {

    /** Default number of priced decisions in the rolling window. */
    public static final int DEFAULT_WINDOW = 1000;

    /** Upper bound on tracked lanes, so a lane string cannot become an unbounded key space. */
    public static final int MAX_LANES = 8;

    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;

    private final int window;

    private final Map<String, Deque<BigDecimal>> lanes = new ConcurrentHashMap<>();

    @Inject
    public DecisionCostGuard(
            @ConfigProperty(name = "amsha.decision.cost.window", defaultValue = "" + DEFAULT_WINDOW) int window) {
        if (window < 1) {
            throw new IllegalArgumentException("cost window must be >= 1, got " + window);
        }
        this.window = window;
    }

    /**
     * Records the cost of one priced decision.
     *
     * @param lane the provider lane; null or blank is ignored
     * @param costUsd the priced cost, or null when the decision could not be priced
     */
    public void record(String lane, Double costUsd) {
        if (lane == null || lane.isBlank() || costUsd == null) {
            return;
        }
        if (costUsd < 0.0) {
            throw new IllegalArgumentException("costUsd must not be negative, got " + costUsd);
        }
        Deque<BigDecimal> window0 = windowFor(lane);
        if (window0 == null) {
            return;
        }
        synchronized (window0) {
            window0.addLast(BigDecimal.valueOf(costUsd));
            while (window0.size() > window) {
                window0.removeFirst();
            }
        }
    }

    /**
     * The rolling cost per 1,000 decisions for a lane.
     *
     * <p>Empty until the window is full: a rate over a partial window is not the rate the ceiling was
     * approved against, and reporting one would let a single expensive first decision look like a breach.
     *
     * @param lane the provider lane
     * @return the rolling cost per 1,000 decisions, or empty when it cannot be stated yet
     */
    public Optional<BigDecimal> costPerThousand(String lane) {
        Deque<BigDecimal> window0 = lane == null ? null : lanes.get(lane);
        if (window0 == null) {
            return Optional.empty();
        }
        synchronized (window0) {
            if (window0.size() < window) {
                return Optional.empty();
            }
            BigDecimal total = BigDecimal.ZERO;
            for (BigDecimal cost : window0) {
                total = total.add(cost);
            }
            return Optional.of(total.divide(BigDecimal.valueOf(window0.size()), MATH_CONTEXT)
                    .multiply(BigDecimal.valueOf(1000), MATH_CONTEXT));
        }
    }

    /**
     * Whether a lane's rolling cost per 1,000 decisions exceeds an approved ceiling.
     *
     * <p>False when no ceiling is approved, when the window is not yet full, or when the lane has never
     * been priced — an unknown rate is not a breach.
     *
     * @param lane the provider lane
     * @param ceilingPerThousand the approved ceiling, or null when none is declared
     * @return whether the ceiling is exceeded
     */
    public boolean exceeded(String lane, BigDecimal ceilingPerThousand) {
        if (ceilingPerThousand == null) {
            return false;
        }
        return costPerThousand(lane)
                .map(observed -> observed.compareTo(ceilingPerThousand) > 0)
                .orElse(false);
    }

    /** How many priced decisions the lane's window currently holds. */
    public int windowSampleCount(String lane) {
        Deque<BigDecimal> window0 = lane == null ? null : lanes.get(lane);
        if (window0 == null) {
            return 0;
        }
        synchronized (window0) {
            return window0.size();
        }
    }

    /** How many lanes are currently tracked; bounded by {@link #MAX_LANES}. */
    public int trackedLaneCount() {
        return lanes.size();
    }

    /** Clears a lane's rolling window. */
    public void reset(String lane) {
        if (lane != null) {
            lanes.remove(lane);
        }
    }

    /** Clears every lane's rolling window. */
    public void resetAll() {
        lanes.clear();
    }

    private Deque<BigDecimal> windowFor(String lane) {
        Deque<BigDecimal> existing = lanes.get(lane);
        if (existing != null) {
            return existing;
        }
        if (lanes.size() >= MAX_LANES) {
            return null;
        }
        return lanes.computeIfAbsent(lane, _ -> new ArrayDeque<>());
    }

    @Override
    public String toString() {
        return "DecisionCostGuard[window=" + window + ", lanes=" + lanes.size() + "]";
    }
}
