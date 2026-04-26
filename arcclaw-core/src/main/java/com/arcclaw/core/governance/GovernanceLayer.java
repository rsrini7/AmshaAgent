package com.arcclaw.core.governance;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Tracks AI consumption costs across all active sessions.
 * Implements hard financial limits and downgrades autonomy when exceeded.
 */
@ApplicationScoped
public class GovernanceLayer implements GovernanceStrategy {

    private static final Logger log = Logger.getLogger(GovernanceLayer.class);

    private static final double WARNING_THRESHOLD = 0.80;

    private final ConcurrentHashMap<String, SessionBudget> budgets = new ConcurrentHashMap<>();

    @Inject
    MeterRegistry registry;

    @PostConstruct
    void initMetrics() {
        registry.gauge("arcclaw.governance.budget.map.size", budgets, Map::size);
    }

    @Scheduled(every = "1h")
    void cleanupStaleBudgets() {
        int maxAgeHours = 24;
        cleanupStaleBudgets(maxAgeHours);
    }

    void cleanupStaleBudgets(int maxAgeHours) {
        Instant cutoff = Instant.now().minus(maxAgeHours, ChronoUnit.HOURS);
        int evicted = 0;
        
        // entrySet().removeIf is safe for ConcurrentHashMap
        var it = budgets.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (entry.getValue().getLastAccessedAt().isBefore(cutoff)) {
                it.remove();
                evicted++;
            }
        }
        
        if (evicted > 0) {
            log.infof("Cleaned up %d stale budget(s) older than %d hours.", evicted, maxAgeHours);
            registry.counter("arcclaw.governance.budget.evictions").increment(evicted);
        }
    }

    Map<String, SessionBudget> getBudgetsInternal() {
        return budgets;
    }

    @ConfigProperty(name = "arcclaw.governance.price-per-1k-input", defaultValue = "0.003")
    double pricePer1kInput;

    @ConfigProperty(name = "arcclaw.governance.price-per-1k-output", defaultValue = "0.015")
    double pricePer1kOutput;

    @ConfigProperty(name = "arcclaw.governance.min-tokens-per-call", defaultValue = "10")
    int minTokensFloor;

    @ConfigProperty(name = "arcclaw.governance.budget-usd", defaultValue = "10.00")
    double budgetUsd;

    // The recordLlmCall method is now a public method of GovernanceLayer,
    // to be called directly by AgentLoop or other components.
    private enum BudgetStatus { OK, WARNING, EXCEEDED }
    private record BudgetUpdateResult(BudgetStatus status, double currentCost, double hardLimit) {}

    public void recordLlmCall(String sessionId, int inputTokens, int outputTokens) {
        if (sessionId == null) {
            return;
        }

        boolean zeroReport = (inputTokens == 0 && outputTokens == 0);
        long effectiveInput = zeroReport ? minTokensFloor : inputTokens;
        long effectiveOutput = zeroReport ? minTokensFloor : outputTokens;

        if (zeroReport) {
            log.debugf("GOVERNANCE prose-extraction step for session %s - applying token floor %d", sessionId, minTokensFloor);
        }

        double incrementalCost = (effectiveInput / 1000.0) * pricePer1kInput + (effectiveOutput / 1000.0) * pricePer1kOutput;

        final BudgetUpdateResult[] resultWrapper = new BudgetUpdateResult[1];
        budgets.compute(sessionId, (id, existing) -> {
            SessionBudget budget = (existing == null) ? new SessionBudget(budgetUsd, WARNING_THRESHOLD) : existing;
            budget.setLastAccessedAt(Instant.now());

            // 1. HARD LIMIT CHECK (Before applying update)
            if (budget.totalCost >= budget.hardLimit) {
                resultWrapper[0] = new BudgetUpdateResult(BudgetStatus.EXCEEDED, budget.totalCost, budget.hardLimit);
                return budget;
            }

            // 2. Apply Update
            budget.input += effectiveInput;
            budget.output += effectiveOutput;
            budget.totalCost += incrementalCost;

            double currentCost = budget.totalCost;

            log.debugf("Recorded LLM call for session %s: in=%d, out=%d, cost=$%.6f, total=$%.4f",
                    sessionId, inputTokens, outputTokens, incrementalCost, currentCost);

            // 3. HARD LIMIT CHECK (After applying update)
            if (currentCost >= budget.hardLimit) {
                log.warnf("Session %s EXCEEDED budget ($%.2f >= $%.2f).",
                        sessionId, currentCost, budget.hardLimit);
                resultWrapper[0] = new BudgetUpdateResult(BudgetStatus.EXCEEDED, currentCost, budget.hardLimit);
                return budget;
            }

            // 4. SOFT WARNING CHECK
            if (currentCost >= budget.warningLimit && !budget.warningSent) {
                budget.warningSent = true;
                log.warnf("Session %s approaching budget limit: $%.4f / $%.2f (%.0f%%)",
                        sessionId, currentCost, budget.hardLimit,
                        (currentCost / budget.hardLimit) * 100);
                resultWrapper[0] = new BudgetUpdateResult(BudgetStatus.WARNING, currentCost, budget.hardLimit);
                return budget;
            }

            resultWrapper[0] = new BudgetUpdateResult(BudgetStatus.OK, currentCost, budget.hardLimit);
            return budget;
        });

        BudgetUpdateResult result = resultWrapper[0];
        if (result.status == BudgetStatus.EXCEEDED) {
            throw new BudgetExceededException(
                    String.format("Session budget of $%.2f exceeded. Total cost: $%.4f", result.hardLimit,
                            result.currentCost));
        }

        if (result.status == BudgetStatus.WARNING) {
            throw new BudgetWarningException(String.format(
                    "Session budget is %.0f%% consumed ($%.4f of $%.2f). " +
                    "Responses will be concise to conserve budget.",
                    (result.currentCost / result.hardLimit) * 100, result.currentCost, result.hardLimit));
        }
    }



    /**
     * Clears tracked budget for a session (usually called when session terminates).
     */
    public void clearSession(String sessionId) {
        budgets.remove(sessionId);
    }

    /**
     * Gets the total tracked USD cost for a given session.
     */
    public double getSessionCost(String sessionId) {
        SessionBudget budget = budgets.get(sessionId);
        if (budget != null) {
            budget.setLastAccessedAt(Instant.now());
            return budget.getTotalCost();
        }
        return 0.0;
    }

    public double getBudgetUsd() { 
        return budgetUsd; 
    }

    public int getSessionInputTokens(String sessionId) {
        SessionBudget b = budgets.get(sessionId);
        if (b != null) {
            b.setLastAccessedAt(Instant.now());
            return (int) b.getInput();
        }
        return 0;
    }

    public int getSessionOutputTokens(String sessionId) {
        SessionBudget b = budgets.get(sessionId);
        if (b != null) {
            b.setLastAccessedAt(Instant.now());
            return (int) b.getOutput();
        }
        return 0;
    }

    /**
     * Checks if a session is currently over its budget limit.
     * Throws BudgetExceededException if already over limit.
     */
    public void assertWithinBudget(String sessionId) {
        SessionBudget budget = budgets.get(sessionId);
        if (budget != null && budget.getTotalCost() >= budget.hardLimit) {
            throw new BudgetExceededException("Budget for session " + sessionId + " is already exceeded");
        }
    }

    /**
     * Internal state representation of a session's budget.
     */
    static final class SessionBudget {
        volatile long input = 0;
        volatile long output = 0;
        final double hardLimit;
        final double warningLimit;
        volatile boolean warningSent = false;
        volatile double totalCost = 0.0;
        volatile Instant lastAccessedAt = Instant.now();

        SessionBudget(double hardLimit, double warningThreshold) {
            this.hardLimit = hardLimit;
            this.warningLimit = hardLimit * warningThreshold;
        }

        Instant getLastAccessedAt() {
            return this.lastAccessedAt;
        }

        void setLastAccessedAt(Instant lastAccessedAt) {
            this.lastAccessedAt = lastAccessedAt;
        }

        double getTotalCost() {
            return this.totalCost;
        }

        long getInput() {
            return this.input;
        }

        long getOutput() {
            return this.output;
        }
    }
}
