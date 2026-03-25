package com.arcclaw.core.governance;

import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

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

        SessionBudget budget = budgets.computeIfAbsent(sessionId, k -> new SessionBudget(budgetUsd, WARNING_THRESHOLD));
        
        synchronized (budget) {
            // Check if already exceeded before doing more work
            if (budget.totalCost >= budget.hardLimit) {
                throw new BudgetExceededException(
                        String.format("Session budget of $%.2f already exceeded.", budget.hardLimit));
            }

            budget.input += effectiveInput;
            budget.output += effectiveOutput;
            budget.totalCost += cost;

            double currentCost = budget.totalCost;

            log.debugf("Recorded LLM call for session %s: in=%d, out=%d, cost=$%.6f, total=$%.4f",
                    sessionId, inputTokens, outputTokens, cost, currentCost);

            // 1. HARD LIMIT 
            if (currentCost >= budget.hardLimit) {
                log.warnf("Session %s EXCEEDED budget ($%.2f >= $%.2f).",
                        sessionId, currentCost, budget.hardLimit);

                throw new BudgetExceededException(
                        String.format("Session budget of $%.2f exceeded. Total cost: $%.4f", budget.hardLimit,
                                currentCost));
            }

            // 2. SOFT WARNING 
            if (currentCost >= budget.warningLimit && !budget.warningSent) {
                budget.warningSent = true;
                log.warnf("Session %s approaching budget limit: $%.4f / $%.2f (%.0f%%)",
                    sessionId, currentCost, budget.hardLimit,
                    (currentCost / budget.hardLimit) * 100);
                throw new BudgetWarningException(String.format(
                    "Session budget is %.0f%% consumed ($%.4f of $%.2f). " +
                    "Responses will be concise to conserve budget.",
                    (currentCost / budget.hardLimit) * 100, currentCost, budget.hardLimit));
            }
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
        return b != null ? (int) b.getInput() : 0;
    }

    public int getSessionOutputTokens(String sessionId) {
        SessionBudget b = budgets.get(sessionId);
        return b != null ? (int) b.getOutput() : 0;
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
     * Synchronized on the object itself to ensure atomic updates to multiple fields.
     */
    private static final class SessionBudget {
        long input = 0;
        long output = 0;
        final double hardLimit;
        final double warningLimit;
        boolean warningSent = false;
        double totalCost = 0.0;

        public SessionBudget(double hardLimit, double warningThreshold) {
            this.hardLimit = hardLimit;
            this.warningLimit = hardLimit * warningThreshold;
        }

        synchronized double getTotalCost() {
            return this.totalCost;
        }

        synchronized long getInput() {
            return this.input;
        }

        synchronized long getOutput() {
            return this.output;
        }
    }
}
