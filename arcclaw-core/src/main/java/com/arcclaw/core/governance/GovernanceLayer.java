package com.arcclaw.core.governance;


import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks AI consumption costs across all active sessions.
 * Implements hard financial limits and downgrades autonomy when exceeded.
 */
@ApplicationScoped
public class GovernanceLayer {

    private static final Logger log = Logger.getLogger(GovernanceLayer.class);

    private static final double WARNING_THRESHOLD = 0.80;

    private final ConcurrentHashMap<String, SessionBudget> budgets = new ConcurrentHashMap<>();

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
    public void recordLlmCall(String sessionId, int inputTokens, int outputTokens) {
        if (sessionId == null) {
            return;
        }

        boolean zeroReport = (inputTokens == 0 && outputTokens == 0);
        long effectiveInput  = zeroReport ? minTokensFloor : inputTokens;
        long effectiveOutput = zeroReport ? minTokensFloor : outputTokens;

        if (zeroReport) {
            log.warnf("GOVERNANCE zero token report for session %s — applying " +
                "floor %d. Verify log-requests config or provider token reporting.",
                sessionId, minTokensFloor);
        }

        double cost = (effectiveInput / 1000.0) * pricePer1kInput + (effectiveOutput / 1000.0) * pricePer1kOutput;

        SessionBudget budget = budgets.computeIfAbsent(sessionId, k -> new SessionBudget());
        budget.input.addAndGet((int) effectiveInput);
        budget.output.addAndGet((int) effectiveOutput);
        budget.addCost(cost);

        double currentCost = budget.getTotalCost();

        log.debugf("Recorded LLM call for session %s: in=%d, out=%d, cost=$%.6f, total=$%.4f",
                sessionId, inputTokens, outputTokens, cost, currentCost);

        // 1. SOFT WARNING FIRST
        if (!budget.warningSent && currentCost >= budgetUsd * WARNING_THRESHOLD) {
            budget.warningSent = true;
            log.warnf("Session %s approaching budget limit: $%.4f / $%.2f (%.0f%%)",
                sessionId, currentCost, budgetUsd,
                (currentCost / budgetUsd) * 100);
            throw new BudgetWarningException(String.format(
                "Session budget is %.0f%% consumed ($%.4f of $%.2f). " +
                "Responses will be concise to conserve budget.",
                (currentCost / budgetUsd) * 100, currentCost, budgetUsd));
        }

        // 2. HARD LIMIT SECOND
        if (currentCost > budgetUsd) {
            log.warnf("Session %s EXCEEDED budget ($%.2f > $%.2f). Downgrading to READ_ONLY.",
                    sessionId, budget.getTotalCost(), budgetUsd);

            throw new BudgetExceededException(
                    String.format("Session budget of $%.2f exceeded. Total cost: $%.4f", budgetUsd,
                            budget.getTotalCost()));
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
        return budget != null ? budget.getTotalCost() : 0.0;
    }

    public double getBudgetUsd() { 
        return budgetUsd; 
    }

    public int getSessionInputTokens(String sessionId) {
        SessionBudget b = budgets.get(sessionId);
        return b != null ? b.input.get() : 0;
    }

    public int getSessionOutputTokens(String sessionId) {
        SessionBudget b = budgets.get(sessionId);
        return b != null ? b.output.get() : 0;
    }

    /**
     * Internal state representation of a session's budget.
     * Uses AtomicIntegers for tokens, and synchronized `addCost` for doubles
     * to avoid CAS loop complexity overhead on primitive doubles.
     */
    private static final class SessionBudget {
        final AtomicInteger input = new AtomicInteger(0);
        final AtomicInteger output = new AtomicInteger(0);
        volatile boolean warningSent = false;

        private double totalCost = 0.0;

        synchronized void addCost(double cost) {
            this.totalCost += cost;
        }

        synchronized double getTotalCost() {
            return this.totalCost;
        }
    }
}
