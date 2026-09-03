package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import com.amsha.core.agent.result.AgentResult;
import com.amsha.persistence.api.CronStorePort;

import org.junit.jupiter.api.Test;

class CronSessionStatusSyncTest {
    private static final String JOB_ID = "12345678-1234-1234-1234-123456789012";

    @Test
    void rejectsShortCronSessionId() {
        CronStorePort store = mock(CronStorePort.class);

        assertFalse(CronSessionStatusSync.syncRunning("cron-short", store));
        verifyNoInteractions(store);
    }

    @Test
    void rejectsLegacyCronSessionWithoutOwnerToken() {
        CronStorePort store = mock(CronStorePort.class);

        assertFalse(CronSessionStatusSync.syncRunning("cron-" + JOB_ID, store));
        verifyNoInteractions(store);
    }

    @Test
    void rejectsLegacyTerminalTransitionWithoutOwnerToken() {
        CronStorePort store = mock(CronStorePort.class);

        assertFalse(
                CronSessionStatusSync.syncTerminal("cron-" + JOB_ID, AgentResult.finished("done", List.of()), store));
        verifyNoInteractions(store);
    }
}
