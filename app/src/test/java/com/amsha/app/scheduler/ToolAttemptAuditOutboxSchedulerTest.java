package com.amsha.app.scheduler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.amsha.core.tool.attempt.ToolAttemptAuditOutboxRelay;

import org.junit.jupiter.api.Test;

class ToolAttemptAuditOutboxSchedulerTest {
    @Test
    void enabledSchedulerSweepsPendingAuditEvents() {
        ToolAttemptAuditOutboxRelay relay = mock(ToolAttemptAuditOutboxRelay.class);
        when(relay.sweepAll()).thenReturn(2);
        ToolAttemptAuditOutboxScheduler scheduler = scheduler(relay, true);

        scheduler.deliverPendingAuditEvents();

        verify(relay).sweepAll();
    }

    @Test
    void disabledSchedulerDoesNotSweep() {
        ToolAttemptAuditOutboxRelay relay = mock(ToolAttemptAuditOutboxRelay.class);
        ToolAttemptAuditOutboxScheduler scheduler = scheduler(relay, false);

        scheduler.deliverPendingAuditEvents();

        verifyNoInteractions(relay);
    }

    private static ToolAttemptAuditOutboxScheduler scheduler(ToolAttemptAuditOutboxRelay relay, boolean enabled) {
        ToolAttemptAuditOutboxScheduler scheduler = new ToolAttemptAuditOutboxScheduler();
        scheduler.relay = relay;
        scheduler.schedulerEnabled = enabled;
        return scheduler;
    }
}
