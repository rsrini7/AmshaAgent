package com.arcclaw.core.governance;

import com.arcclaw.core.observability.AgentObserver;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.scope.WorkspaceScoped;
import com.arcclaw.core.security.AutonomyLevel;
import io.quarkus.arc.Arc;
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
public class GovernanceLayer implements AgentObserver {

    private static final Logger log = Logger.getLogger(GovernanceLayer.class);

    private final ConcurrentHashMap<String, SessionBudget> budgets = new ConcurrentHashMap<>();

    @ConfigProperty(name = "arcclaw.governance.price-per-1k-input", defaultValue = "0.003")
    double pricePer1kInput;

    @ConfigProperty(name = "arcclaw.governance.price-per-1k-output", defaultValue = "0.015")
    double pricePer1kOutput;

    @ConfigProperty(name = "arcclaw.governance.budget-usd", defaultValue = "10.00")
    double budgetUsd;

    @Override
    public void recordLlmCall(String sessionId, int inputTokens, int outputTokens) {
        if (sessionId == null) {
            return;
        }

        double cost = (inputTokens / 1000.0) * pricePer1kInput + (outputTokens / 1000.0) * pricePer1kOutput;

        SessionBudget budget = budgets.computeIfAbsent(sessionId, k -> new SessionBudget());
        budget.input.addAndGet(inputTokens);
        budget.output.addAndGet(outputTokens);
        budget.addCost(cost);

        log.debugf("Recorded LLM call for session %s: in=%d, out=%d, cost=$%.6f, total=$%.4f",
                sessionId, inputTokens, outputTokens, cost, budget.getTotalCost());

        if (budget.getTotalCost() > budgetUsd) {
            log.warnf("Session %s EXCEEDED budget ($%.2f > $%.2f). Downgrading to READ_ONLY.",
                    sessionId, budget.getTotalCost(), budgetUsd);

            downgradeAutonomy();

            throw new BudgetExceededException(
                    String.format("Session budget of $%.2f exceeded. Total cost: $%.4f", budgetUsd,
                            budget.getTotalCost()));
        }
    }

    /**
     * Looks up the current WorkspaceContext and downgrades it to READ_ONLY.
     */
    private void downgradeAutonomy() {
        try {
            var scopeCtx = Arc.container().getActiveContext(WorkspaceScoped.class);
            if (scopeCtx != null && scopeCtx.isActive()) {
                // To get the bean instance, we lookup via Arc
                var handle = Arc.container().instance(WorkspaceContext.class);
                if (handle.isAvailable()) {
                    handle.get().setAutonomyLevel(AutonomyLevel.READONLY);
                }
            }
        } catch (Exception e) {
            log.warnf("Failed to downgrade autonomy: %s", e.getMessage());
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

    /**
     * Internal state representation of a session's budget.
     * Uses AtomicIntegers for tokens, and synchronized `addCost` for doubles
     * to avoid CAS loop complexity overhead on primitive doubles.
     */
    private static final class SessionBudget {
        final AtomicInteger input = new AtomicInteger(0);
        final AtomicInteger output = new AtomicInteger(0);

        private double totalCost = 0.0;

        synchronized void addCost(double cost) {
            this.totalCost += cost;
        }

        synchronized double getTotalCost() {
            return this.totalCost;
        }
    }
}
