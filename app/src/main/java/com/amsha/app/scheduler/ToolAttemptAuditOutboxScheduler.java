package com.amsha.app.scheduler;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.amsha.core.tool.attempt.ToolAttemptAuditOutboxRelay;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;

/** Retries durable tool-attempt audit deliveries whose synchronous caller did not complete. */
@ApplicationScoped
public class ToolAttemptAuditOutboxScheduler {
    static final String ENABLED_PROPERTY = "amsha.tools.attempt.audit-outbox-enabled";
    static final String CRON_PROPERTY = "amsha.tools.attempt.audit-outbox-cron";

    private static final Logger log = Logger.getLogger(ToolAttemptAuditOutboxScheduler.class);

    @Inject
    ToolAttemptAuditOutboxRelay relay;

    @ConfigProperty(name = ENABLED_PROPERTY, defaultValue = "true")
    boolean schedulerEnabled;

    @Scheduled(cron = "{" + CRON_PROPERTY + ":0 * * * * ?}", concurrentExecution = ConcurrentExecution.SKIP)
    void deliverPendingAuditEvents() {
        if (!schedulerEnabled) {
            return;
        }
        int delivered = relay.sweepAll();
        if (delivered > 0) {
            log.infof("[ToolAttemptAuditOutbox] Delivered %d pending audit events", delivered);
        }
    }
}
