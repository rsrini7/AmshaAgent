package com.amsha.app.recovery;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.amsha.app.db.ToolAttemptSchemaGuard;
import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.loop.StartupAttemptReconciler;

import org.jboss.logging.Logger;

import io.quarkus.runtime.StartupEvent;

/**
 * Startup observer that triggers tool attempt reconciliation after schema contract verification.
 * Per Proposal §10 Phase C item 4 and Proposal §12.8.
 */
@ApplicationScoped
public class StartupAttemptReconciliationObserver {

    private static final Logger log = Logger.getLogger(StartupAttemptReconciliationObserver.class);

    private final ToolAttemptSchemaGuard schemaGuard;
    private final StartupAttemptReconciler reconciler;

    @Inject
    public StartupAttemptReconciliationObserver(
            @Any Instance<ToolAttemptSchemaGuard> schemaGuardInstance,
            Instance<StartupAttemptReconciler> reconcilerInstance) {
        this.schemaGuard = schemaGuardInstance.isResolvable() ? schemaGuardInstance.get() : null;
        this.reconciler = reconcilerInstance.isResolvable() ? reconcilerInstance.get() : null;
    }

    StartupAttemptReconciliationObserver(ToolAttemptSchemaGuard schemaGuard, StartupAttemptReconciler reconciler) {
        this.schemaGuard = schemaGuard;
        this.reconciler = reconciler;
    }

    /**
     * Executes at Priority 30, strictly after ToolAttemptSchemaGuard at Priority 20.
     */
    void onStartup(@Observes @Priority(30) StartupEvent event) {
        if (schemaGuard == null || !schemaGuard.isVerified()) {
            throw new IllegalStateException("Startup halted: ToolAttemptSchemaGuard did not verify schema contract.");
        }
        if (reconciler == null) {
            throw new IllegalStateException("Startup halted: StartupAttemptReconciler is not resolvable.");
        }
        try {
            reconciler.reconcileAllWorkspaces();
        } catch (Exception e) {
            log.errorf(LogUtil.relevantThrowable(e), "Startup attempt reconciliation failed");
            throw new IllegalStateException("Startup attempt reconciliation failed", e);
        }
    }
}
