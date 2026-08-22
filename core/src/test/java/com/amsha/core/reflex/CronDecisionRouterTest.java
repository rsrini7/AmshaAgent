package com.amsha.core.reflex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.core.agent.loop.AgentLoop;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.lifecycle.WorkspaceManager;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.reflex.CronDecisionRouter.CronContext;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CronRunDto;
import com.amsha.persistence.api.CronRunStorePort;
import com.amsha.persistence.api.CronStorePort;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link CronDecisionRouter} execution branches with mocked collaborators.
 */
public class CronDecisionRouterTest {

    private static final String CRON_ID = "0123456789abcdef0123456789abcdef0123";

    @Mock
    StatefulAgentStore statefulAgentStore;

    @Mock
    CronStorePort cronStore;

    @Mock
    CronRunStorePort cronRunStore;

    @Mock
    AgentLoop agentLoop;

    @Mock
    ReasoningEngine reasoningEngine;

    @Mock
    WorkspaceManager workspaceManager;

    @InjectMocks
    CronDecisionRouter router;

    private AutoCloseable mocks;

    @BeforeEach
    public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
    }

    @AfterEach
    public void tearDown() throws Exception {
        mocks.close();
    }

    private AgentCheckpoint checkpoint() {
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getSessionId()).thenReturn("cron-" + CRON_ID);
        when(cp.getAgentRole()).thenReturn("coder");
        when(cp.getMaxIterations()).thenReturn(6);
        when(cp.getSystemNote()).thenReturn("note");
        return cp;
    }

    private AgentCronDto cronDto() {
        return new AgentCronDto(
                CRON_ID,
                null,
                "user-1",
                "chat-1",
                "TELEGRAM",
                "42",
                "0 * * * *",
                "UTC",
                false,
                "goal",
                Instant.now(),
                Instant.now(),
                "ACTIVE",
                "coder",
                null,
                null,
                Instant.now(),
                Instant.now());
    }

    private CronContext context(AgentCheckpoint cp, String reason) {
        MessageContext messageContext = mock(MessageContext.class);
        return new CronContext(
                CRON_ID,
                "user-1",
                "chat-1",
                reason,
                messageContext,
                (sessionId, r) -> cp,
                () -> "already processing",
                (sessionId, role) -> "resume goal",
                (cron, r, result) -> {},
                result -> "ok:" + result,
                () -> "loop failed",
                (cron, result) -> {});
    }

    @Test
    public void returnsNullWhenCronSessionAlreadyActive() throws Exception {
        when(statefulAgentStore.isSessionActive(anyString())).thenReturn(true);

        assertNull(router.execute(context(checkpoint(), "retry")));
        verify(agentLoop, never()).run(any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    public void reportsMissingCronJob() throws Exception {
        when(statefulAgentStore.isSessionActive(anyString())).thenReturn(false);
        when(statefulAgentStore.findLatestAwaitingApprovalBySessionContaining(anyString()))
                .thenReturn(Optional.empty());
        when(cronStore.findById(anyString())).thenReturn(Optional.empty());

        String response = router.execute(context(checkpoint(), "approve"));

        assertEquals("❌ Cron task not found.", response);
        verify(statefulAgentStore, never()).claimForResume(anyString(), anyString(), anyBoolean());
    }

    @Test
    public void reportsAlreadyProcessingWhenClaimFails() throws Exception {
        when(statefulAgentStore.isSessionActive(anyString())).thenReturn(false);
        when(statefulAgentStore.findLatestAwaitingApprovalBySessionContaining(anyString()))
                .thenReturn(Optional.empty());
        when(cronStore.findById(CRON_ID)).thenReturn(Optional.of(cronDto()));
        when(statefulAgentStore.claimForResume(anyString(), anyString())).thenReturn(false);

        String response = router.execute(context(checkpoint(), "deny"));

        assertEquals("already processing", response);
    }

    @Test
    public void nullLoopResultYieldsErrorMessageAndStillClearsResumeFlag() throws Exception {
        when(statefulAgentStore.isSessionActive(anyString())).thenReturn(false);
        when(statefulAgentStore.findLatestAwaitingApprovalBySessionContaining(anyString()))
                .thenReturn(Optional.empty());
        when(cronStore.findById(CRON_ID)).thenReturn(Optional.of(cronDto()));
        when(statefulAgentStore.claimForResume(anyString(), anyString())).thenReturn(true);
        when(agentLoop.run(any(), any(), any(), any(), any(), anyInt(), any())).thenReturn(null);

        String response = router.execute(context(checkpoint(), "resume"));

        assertEquals("loop failed", response);
        verify(statefulAgentStore).clearBatchResuming("cron-" + CRON_ID);
        verify(workspaceManager, never()).terminateSession(any());
    }

    @Test
    public void finishedResultUpdatesRunNotifiesAndTerminatesSession() throws Exception {
        when(statefulAgentStore.isSessionActive(anyString())).thenReturn(false);
        when(statefulAgentStore.findLatestAwaitingApprovalBySessionContaining(anyString()))
                .thenReturn(Optional.empty());
        when(cronStore.findById(CRON_ID)).thenReturn(Optional.of(cronDto()));
        when(statefulAgentStore.claimForResume(anyString(), anyString())).thenReturn(true);
        AgentResult.Finished finished = new AgentResult.Finished("all done", List.of(), 10, 5);
        when(agentLoop.run(any(), any(), any(), any(), any(), anyInt(), any())).thenReturn(finished);

        CronRunDto awaitingRun = new CronRunDto(
                "run-1",
                CRON_ID,
                "cron-" + CRON_ID,
                "AWAITING_APPROVAL",
                Instant.now(),
                null,
                null,
                2,
                0,
                0,
                null,
                Instant.now());
        when(cronRunStore.findAll(eq("AWAITING_APPROVAL"), anyInt(), anyInt())).thenReturn(List.of(awaitingRun));

        boolean[] notified = {false};
        CronContext ctx = new CronContext(
                CRON_ID,
                "user-1",
                "chat-1",
                "resume",
                mock(MessageContext.class),
                (sessionId, r) -> checkpoint(),
                () -> "already processing",
                (sessionId, role) -> "resume goal",
                (cron, r, result) -> {},
                result -> "ok:" + result,
                () -> "loop failed",
                (cron, result) -> notified[0] = true);

        String response = router.execute(ctx);

        assertEquals("ok:" + finished, response);
        assertTrue(notified[0]);
        verify(cronRunStore)
                .save(ArgumentMatchers.argThat(
                        run -> "COMPLETED".equals(run.status()) && run.inputTokens() == 10 && run.outputTokens() == 5));
        verify(workspaceManager).terminateSession("cron-" + CRON_ID);
        verify(statefulAgentStore).clearBatchResuming("cron-" + CRON_ID);
    }
}
