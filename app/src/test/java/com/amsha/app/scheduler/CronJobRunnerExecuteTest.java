package com.amsha.app.scheduler;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.Future;

import com.amsha.core.agent.loop.AgentLoop;
import com.amsha.core.agent.reasoning.LangChainReasoningEngine;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.domain.model.AgentCron;
import com.amsha.core.lifecycle.SessionHeartbeatTracker;
import com.amsha.persistence.api.CronRunStorePort;
import com.amsha.persistence.api.CronStorePort;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link CronJobRunner#execute(AgentCron)} — the orchestration surface that was previously dark.
 *
 * <p>The five private helpers ({@code isPureAnnouncement}, {@code isTimeCheck}, {@code stripAnnouncementPrefix},
 * {@code unwrapToolCallGoal}, {@code resolveRole}) are already covered by {@code CronJobRunnerDeepTest} via
 * reflection. This class covers the public {@code execute} path: the run-setup, the announcement and time-check
 * short-circuits, the main agent-loop delegation through {@code WorkspaceContext.run}, the tenant-scoped
 * {@code createCronContext} validation, and the {@code catch}/{@code finally} failure handling.
 *
 * <p>Collaborators are injected by reflection (the class is {@code @ApplicationScoped} and unit-testable without
 * Quarkus — {@code WorkspaceContext.run} binds plain {@code ScopedValue}s and {@code Arc.container()} returns
 * {@code null} outside a running container, which the holder tolerates). The lease-renewal background thread is
 * stubbed out: {@code vThreads.submit(...)} returns a mock {@link Future} without running the lambda, because the
 * lambda's body is guarded by a 60-second {@code Thread.sleep} and is a deliberate exclusion (see the residual
 * table in the coverage notes).
 */
@ExtendWith(MockitoExtension.class)
class CronJobRunnerExecuteTest {

    @Mock
    AgentLoop agentLoop;

    @Mock
    LangChainReasoningEngine engine;

    @Mock
    CronResultHandler resultHandler;

    @Mock
    CronStorePort cronStore;

    @Mock
    CronRunStorePort cronRunStore;

    @Mock
    SessionHeartbeatTracker heartbeatTracker;

    @Mock
    ManagedExecutor vThreads;

    private CronJobRunner runner;

    @BeforeEach
    void setUp() throws Exception {
        runner = new CronJobRunner();
        runner.cronMaxIterations = 5;
        inject("agentLoop", agentLoop);
        inject("engine", engine);
        inject("resultHandler", resultHandler);
        inject("cronStore", cronStore);
        inject("cronRunStore", cronRunStore);
        inject("heartbeatTracker", heartbeatTracker);
        inject("vThreads", vThreads);
        // The lease lambda is never executed; only the submission call matters for coverage.
        // The overload invoked is submit(Runnable) (the lease lambda has no return value; WorkspaceScopeHolder
        // returns it as-is when no workspace is active). Stubbing Runnable (not Callable) makes the returned
        // Future non-null so the finally-block leaseFuture.cancel(197) is exercised. lenient() guards against
        // Mockito's synthetic-class strict-stubbing false-positive being thrown into execute()'s catch.
        lenient().when(vThreads.submit(any(Runnable.class))).thenReturn(mock(Future.class));
    }

    private void inject(String field, Object value) throws Exception {
        Field f = CronJobRunner.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(runner, value);
    }

    // The getters are stubbed leniently on purpose: several short-circuit tests (announcement, time-check,
    // unknown-channel, validation-failure) never reach resolveRole(179)/WorkspaceContext.run(188), so
    // getUserId/getChannelPlatformId/getChatId/getExecutionRole are never read. Without lenient() Mockito's
    // strict-stubbing check would flag them as UnnecessaryStubbing for those tests. The getters that are
    // always exercised (getId/getGoal/getChannelType) are harmless to also stub leniently here.
    private AgentCron job(String goal, String channelType, String platformId, String chatId) {
        AgentCron job = mock(AgentCron.class);
        lenient().when(job.getId()).thenReturn("1");
        lenient().when(job.getGoal()).thenReturn(goal);
        lenient().when(job.getChannelType()).thenReturn(channelType);
        lenient().when(job.getUserId()).thenReturn("user-1");
        lenient().when(job.getChannelPlatformId()).thenReturn(platformId);
        lenient().when(job.getChatId()).thenReturn(chatId);
        lenient().when(job.getExecutionRole()).thenReturn(null);
        return job;
    }

    @Test
    @DisplayName("the one-arg overload delegates to the executionId overload")
    void oneArgOverloadDelegates() {
        AgentCron job = job("print hello world", "telegram", "plat-1", "chat-1");

        runner.execute(job);

        verify(resultHandler).handleSuccess(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a pure announcement goal short-circuits to handleSuccess without entering the agent loop")
    void announcementShortCircuit() {
        AgentCron job = job("print hello world", "telegram", "plat-1", "chat-1");

        runner.execute(job, "exec-1");

        verify(agentLoop, never()).run(any(), any(), any(), any(), any(), anyInt(), any());
        verify(resultHandler).handleSuccess(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a time-check goal short-circuits to handleSuccess")
    void timeCheckShortCircuit() {
        AgentCron job = job("what is the current time", "telegram", "plat-1", "chat-1");

        runner.execute(job, "exec-2");

        verify(agentLoop, never()).run(any(), any(), any(), any(), any(), anyInt(), any());
        verify(resultHandler).handleSuccess(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a normal goal runs through WorkspaceContext and the agent loop to handleSuccess")
    void mainPathSuccess() {
        AgentCron job = job("fetch the latest data", "telegram", "plat-1", "chat-1");
        when(agentLoop.run(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(AgentResult.finished("done", Collections.emptyList()));

        runner.execute(job, "exec-3");

        verify(agentLoop).run(any(), any(), any(), any(), any(), anyInt(), any());
        verify(resultHandler).handleSuccess(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a tool-call JSON goal is unwrapped before the agent loop runs, exercising the CRON-UNWRAP log")
    void unwrappedToolCallGoalRunsThroughAgentLoop() {
        // Valid JSON with a "goal" key -> unwrapToolCallGoal extracts the inner goal, so finalGoal != stripped
        // and the CRON-UNWRAP log (174) fires before the main path.
        AgentCron job = job("{\"goal\":\"fetch the latest data\"}", "telegram", "plat-1", "chat-1");
        when(agentLoop.run(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(AgentResult.finished("done", Collections.emptyList()));

        runner.execute(job, "exec-9");

        verify(agentLoop).run(any(), any(), any(), any(), any(), anyInt(), any());
        verify(resultHandler).handleSuccess(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a missing or unknown channel type is rejected and routed to handleFailure")
    void unknownChannelTypeFails() {
        AgentCron job = job("fetch the latest data", "bogus-channel", "plat-1", "chat-1");

        runner.execute(job, "exec-4");

        verify(agentLoop, never()).run(any(), any(), any(), any(), any(), anyInt(), any());
        verify(resultHandler).handleFailure(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a job with neither platform id nor chat id fails createCronContext validation")
    void missingPlatformAndChatIdFails() {
        AgentCron job = job("fetch the latest data", "telegram", null, null);

        runner.execute(job, "exec-5");

        verify(agentLoop, never()).run(any(), any(), any(), any(), any(), anyInt(), any());
        verify(resultHandler).handleFailure(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a job with a platform id but no chat id fails the chat-id validation")
    void missingChatIdFails() {
        AgentCron job = job("fetch the latest data", "telegram", "plat-1", null);

        runner.execute(job, "exec-6");

        verify(agentLoop, never()).run(any(), any(), any(), any(), any(), anyInt(), any());
        verify(resultHandler).handleFailure(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("an agent-loop exception is caught and routed to handleFailure")
    void agentLoopFailureIsHandled() {
        AgentCron job = job("fetch the latest data", "telegram", "plat-1", "chat-1");
        when(agentLoop.run(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenThrow(new RuntimeException("agent loop exploded"));

        runner.execute(job, "exec-7");

        verify(resultHandler).handleFailure(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("unwrapToolCallGoal's JSON parse failure falls back to the original goal rather than throwing")
    void unwrapToolCallGoalInvalidJsonWithGoalKey() throws Exception {
        // Starts with "{" AND contains "\"goal\"", but is not valid JSON -> exercises the catch block.
        String goal = "{\"goal\": oops this is not valid json";
        Method m = CronJobRunner.class.getDeclaredMethod("unwrapToolCallGoal", String.class);
        m.setAccessible(true);
        String result = assertDoesNotThrow(() -> (String) m.invoke(runner, goal));
        // Returns the original goal unchanged (the catch logs and falls through).
        Assertions.assertEquals(goal, result);
    }

    @Test
    @DisplayName("execute never throws past its own catch — a handler failure still propagates to handleFailure")
    void executeIsThrowableSafe() {
        AgentCron job = job("fetch the latest data", "telegram", "plat-1", "chat-1");
        when(agentLoop.run(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenThrow(new IllegalStateException("boom"));

        assertDoesNotThrow(() -> runner.execute(job, "exec-8"));

        verify(resultHandler).handleFailure(any(), anyString(), anyString(), any());
    }
}
