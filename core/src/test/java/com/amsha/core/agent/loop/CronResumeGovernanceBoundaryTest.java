package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.channel.api.ChannelPlugin;
import com.amsha.channel.api.ChannelSender;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.channel.ChannelPluginRegistry;
import com.amsha.core.domain.model.AgentCheckpointRecord;
import com.amsha.core.event.DomainEventBus;
import com.amsha.core.event.EventTypeCatalog;
import com.amsha.core.governance.ApprovalFrame;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.state.CheckpointSerializer;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CheckpointAppendResult;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CheckpointStorePort;
import com.amsha.persistence.api.CronRunStorePort;
import com.amsha.persistence.api.CronStorePort;
import com.amsha.persistence.api.ReasoningStepDto;
import com.amsha.persistence.api.SessionCatalogStore;
import com.amsha.persistence.api.ToolAttemptDto;
import com.amsha.persistence.api.ToolAttemptIdempotencyMode;
import com.amsha.persistence.api.ToolAttemptStatus;
import com.amsha.persistence.api.ToolAttemptStorePort;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.RiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Boundary tests for Cron Resume Governance Alignment (Step C.4).
 * Verifies workspace identity propagation, attempt reconciliation,
 * retry context restoration, and cron notification / chat isolation.
 */
class CronResumeGovernanceBoundaryTest {

    private static final String CRON_JOB_ID = "12345678-1234-1234-1234-123456789012";
    private static final String CRON_DELIVERY_ID = "delivery-c4-001";
    private static final String CRON_SESSION_ID = "cron-" + CRON_JOB_ID + "-" + CRON_DELIVERY_ID;
    private static final String WORKSPACE_ID = "ws-cron-gov-1";
    private static final String TOOL_NAME = "fetch_report";
    private static final String OPERATION_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String FINGERPRINT =
            "sha256:v1:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private StatefulAgentStore agentStore;
    private AgentLoop agentLoop;
    private CronStorePort cronStore;
    private CronRunStorePort cronRunStore;
    private SessionCatalogStore sessionCatalogStore;
    private ChannelPluginRegistry pluginRegistry;
    private ChannelPlugin channelPlugin;
    private ChannelSender channelSender;
    private DomainEventBus domainEventBus;
    private ResumeCronNotificationDispatcher cronNotificationDispatcher;
    private ObjectMapper objectMapper;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        agentStore = mock(StatefulAgentStore.class);
        agentLoop = mock(AgentLoop.class);
        cronStore = mock(CronStorePort.class);
        cronRunStore = mock(CronRunStorePort.class);
        sessionCatalogStore = mock(SessionCatalogStore.class);
        pluginRegistry = mock(ChannelPluginRegistry.class);
        channelPlugin = mock(ChannelPlugin.class);
        channelSender = mock(ChannelSender.class);
        domainEventBus = mock(DomainEventBus.class);
        objectMapper = new ObjectMapper();
        meterRegistry = new SimpleMeterRegistry();

        when(pluginRegistry.find(anyString())).thenReturn(Optional.of(channelPlugin));
        when(pluginRegistry.find(any(ChannelType.class))).thenReturn(Optional.of(channelPlugin));
        when(channelPlugin.sender()).thenReturn(channelSender);

        cronNotificationDispatcher = new ResumeCronNotificationDispatcher(cronStore, pluginRegistry, domainEventBus);
    }

    @Test
    @DisplayName("Rate-limit cron retry restores history, effective goal, and propagates active workspace")
    void testRateLimitCronRetryRestoresHistoryAndPropagatesActiveWorkspace() throws Exception {
        RateLimitRetryScheduler scheduler = new RateLimitRetryScheduler();
        scheduler.agentStore = agentStore;
        scheduler.agentLoop = agentLoop;
        scheduler.cronStore = cronStore;
        scheduler.cronRunStore = cronRunStore;
        scheduler.sessionCatalogStore = sessionCatalogStore;
        scheduler.channelPluginRegistry = pluginRegistry;
        scheduler.meterRegistry = meterRegistry;
        scheduler.cronNotificationDispatcher = cronNotificationDispatcher;
        scheduler.defaultRetryDelayMs = 1000;
        scheduler.init();

        AgentCheckpointRecord cp = new AgentCheckpointRecord();
        cp.setId(500L);
        cp.setSessionId(CRON_SESSION_ID);
        cp.setAgentRole("coordinator");
        cp.setOriginalGoal("Generate daily cron summary");
        cp.setUpdatedAt(Instant.now().minusSeconds(10));
        cp.setRetryAfterMs(1000);
        cp.setChannelType("cli");
        cp.setChannelPlatformId("cron-operator");

        AgentCronDto cronDto = new AgentCronDto(
                CRON_JOB_ID,
                CRON_SESSION_ID,
                "operator-1",
                "chat-1",
                "cli",
                "cron-operator",
                "0 0 * * *",
                "UTC",
                false,
                "Generate daily cron summary",
                Instant.now(),
                Instant.now(),
                "RUNNING",
                "coordinator",
                null,
                CRON_DELIVERY_ID,
                Instant.now(),
                Instant.now());

        when(agentStore.findByStatus(CheckpointStatus.AWAITING_RETRY)).thenReturn(List.of(cp));
        when(agentStore.claimForRetry(500L)).thenReturn(true);
        when(cronStore.claimForResume(CRON_JOB_ID, CRON_DELIVERY_ID)).thenReturn(true);
        when(cronStore.findById(CRON_JOB_ID)).thenReturn(Optional.of(cronDto));
        when(cronStore.markFiredIfOwner(eq(CRON_JOB_ID), eq(CRON_DELIVERY_ID), any(), any()))
                .thenReturn(true);
        when(cronStore.markCompletedIfOwner(eq(CRON_JOB_ID), eq(CRON_DELIVERY_ID), any()))
                .thenReturn(true);

        AtomicReference<String> observedWorkspace = new AtomicReference<>();
        AtomicReference<MessageContext> observedContext = new AtomicReference<>();

        when(agentLoop.resume(eq(CRON_SESSION_ID), eq("coordinator"), eq("Generate daily cron summary"), any()))
                .thenAnswer(inv -> {
                    observedWorkspace.set(WorkspaceContext.activeWorkspaceId().orElse(null));
                    observedContext.set(inv.getArgument(3));
                    return new AgentResult.Finished("Daily summary generated", List.of(), 0, 0);
                });

        WorkspaceContext.callWithActiveWorkspace(WORKSPACE_ID, () -> {
            scheduler.processPendingRetries();
            return null;
        });

        assertEquals(WORKSPACE_ID, observedWorkspace.get(), "Active workspace must propagate to resume execution");
        assertNotNull(observedContext.get(), "Retry MessageContext must be provided");
        assertTrue(observedContext.get().isResumeSignal(), "isResumeSignal must be true to restore history");
        assertEquals("Generate daily cron summary", observedContext.get().getGoal());

        verify(channelSender, never()).sendRaw(any(), anyString());
        verify(channelSender, never()).send(any(), any());
        verify(domainEventBus).publish(eq(EventTypeCatalog.AGENT_JOB_COMPLETED), any());
    }

    @Test
    @DisplayName("Approval resume propagates active workspace across async dispatch and synchronous path")
    void testApprovalResumePropagatesActiveWorkspace() throws Exception {
        ManagedExecutor executor = mock(ManagedExecutor.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        CliResultBridge cliResultBridge = mock(CliResultBridge.class);
        ExecutionFailureRecoveryService recoveryService = mock(ExecutionFailureRecoveryService.class);
        PersonaEscalationService escalationService = mock(PersonaEscalationService.class);

        CheckpointResumeHandler handler = new CheckpointResumeHandler(
                agentStore,
                toolRegistry,
                objectMapper,
                agentLoop,
                mock(ReasoningEngine.class),
                pluginRegistry,
                cronStore,
                cronRunStore,
                cliResultBridge,
                recoveryService,
                escalationService);
        handler.executor = executor;
        handler.meterRegistry = meterRegistry;
        handler.cronNotificationDispatcher = cronNotificationDispatcher;

        AgentCheckpointRecord cp = new AgentCheckpointRecord();
        cp.setId(501L);
        cp.setSessionId(CRON_SESSION_ID);
        cp.setAgentRole("coordinator");
        cp.setOriginalGoal("Generate daily cron summary");
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);

        when(agentStore.claimForResume(CRON_SESSION_ID, "coordinator")).thenReturn(true);
        when(agentStore.load(CRON_SESSION_ID, "coordinator")).thenReturn(Optional.of(cp));
        when(cronStore.claimForResume(CRON_JOB_ID, CRON_DELIVERY_ID)).thenReturn(true);

        // 1. Test Async Path (resumeAfterBatchDecision)
        AtomicReference<String> asyncObservedWorkspace = new AtomicReference<>();
        when(agentLoop.run(eq(CRON_SESSION_ID), eq("coordinator"), anyString(), any(), any(), anyInt(), any()))
                .thenAnswer(inv -> {
                    asyncObservedWorkspace.set(
                            WorkspaceContext.activeWorkspaceId().orElse(null));
                    return new AgentResult.Finished("Approved step finished", List.of(), 0, 0);
                });

        doAnswer(inv -> {
                    Runnable r = inv.getArgument(0);
                    Thread t = new Thread(r);
                    t.start();
                    t.join();
                    return null;
                })
                .when(executor)
                .execute(any(Runnable.class));

        WorkspaceContext.callWithActiveWorkspace("ws-async-hitl", () -> {
            boolean accepted =
                    handler.resumeAfterBatchDecision(CRON_SESSION_ID, "coordinator", "operator-1", "chat-1", "cli");
            assertTrue(accepted);
            return null;
        });

        assertEquals(
                "ws-async-hitl",
                asyncObservedWorkspace.get(),
                "Async resume must propagate active workspace across executor dispatch");

        // 2. Test Synchronous Path (resumeAfterBatchDecisionSync)
        AtomicReference<String> syncObservedWorkspace = new AtomicReference<>();
        when(agentLoop.run(eq(CRON_SESSION_ID), eq("coordinator"), anyString(), any(), any(), anyInt(), any()))
                .thenAnswer(inv -> {
                    syncObservedWorkspace.set(
                            WorkspaceContext.activeWorkspaceId().orElse(null));
                    return new AgentResult.Finished("Approved step finished", List.of(), 0, 0);
                });

        WorkspaceContext.callWithActiveWorkspace("ws-sync-hitl", () -> {
            AgentResult result =
                    handler.resumeAfterBatchDecisionSync(CRON_SESSION_ID, "coordinator", "operator-1", "chat-1", "cli");
            assertNotNull(result);
            return null;
        });

        assertEquals(
                "ws-sync-hitl",
                syncObservedWorkspace.get(),
                "Synchronous resume must retain outer active workspace context");
    }

    @Test
    @DisplayName(
            "Production resume boundary: SETTLED bypasses tool execution, INDETERMINATE halts replay, missing WS blocks")
    void testCronResumeAttemptReconciliationProductionWiring() {
        ToolAttemptStorePort attemptStore = mock(ToolAttemptStorePort.class);
        CheckpointStorePort cpStore = mock(CheckpointStorePort.class);
        CheckpointSerializer serializer = mock(CheckpointSerializer.class);
        SequentialAttemptReconciler reconciler =
                new SequentialAttemptReconciler(attemptStore, cpStore, serializer, objectMapper);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        AgentStateMachineRunner stateMachineRunner = mock(AgentStateMachineRunner.class);
        when(stateMachineRunner.runFinishPass(any(), any(), any()))
                .thenReturn(new AgentResult.Finished("Finish pass completed", List.of(), 0, 0));

        CheckpointResumeHandler handler = new CheckpointResumeHandler(
                agentStore,
                toolRegistry,
                objectMapper,
                agentLoop,
                mock(ReasoningEngine.class),
                pluginRegistry,
                cronStore,
                cronRunStore,
                mock(CliResultBridge.class),
                mock(ExecutionFailureRecoveryService.class),
                mock(PersonaEscalationService.class),
                new ToolDelegationResolver(),
                mock(ToolInvocationGuard.class),
                cronNotificationDispatcher,
                reconciler,
                null);

        AgentExecutionContext ctx = new AgentExecutionContext(
                CRON_SESSION_ID,
                "u1",
                "c1",
                "coordinator",
                "daily summary",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                10,
                0,
                "cli",
                "u1",
                null,
                Map.of());

        AgentCheckpointRecord cp = new AgentCheckpointRecord();
        cp.setId(502L);
        cp.setSessionId(CRON_SESSION_ID);
        cp.setAgentRole("coordinator");
        cp.setPendingToolName(TOOL_NAME);
        cp.setPendingToolParametersJson("{\"report\":\"weekly\"}");
        cp.setOriginalGoal("daily summary");
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        cp.setVersion(1);

        when(agentStore.load(CRON_SESSION_ID, "coordinator")).thenReturn(Optional.of(cp));
        when(agentStore.deserializeSteps(cp))
                .thenAnswer(_ -> List.of(ReasoningStep.pending("thought", TOOL_NAME, Map.of("report", "weekly"))
                        .withOperationId(OPERATION_ID)));
        when(serializer.serializeSteps(any())).thenReturn("[]");
        when(serializer.toDto(any())).thenReturn(mock(ReasoningStepDto.class));
        when(cpStore.appendResultIfAbsent(any(), any(), anyInt(), any(), any()))
                .thenReturn(CheckpointAppendResult.APPENDED);

        MessageContext msgCtx = new MessageContext("c1", new ChannelUser("u1", "c1", "User", ChannelType.CLI), null);
        msgCtx.setResumeSignal(true);
        msgCtx.setGoal("daily summary");

        // Case A: Missing Active Workspace -> Fails closed as Blocked, tool is NEVER executed
        List<ReasoningStep> historyNoWs = new ArrayList<>();
        AgentResult noWsResult = handler.handle(ctx, historyNoWs, msgCtx, stateMachineRunner);
        assertInstanceOf(AgentResult.Failed.class, noWsResult);
        verify(toolRegistry, never()).invokeToolWithOperationId(any(), any(), any(), any());
        verify(agentStore).markFailed(eq(cp), contains("Active workspace is required"));

        WorkspaceContext.runWithActiveWorkspace(WORKSPACE_ID, () -> {
            // Case B: Settled Attempt -> Appended once without re-executing tool
            cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
            String settledAttId = "11111111-1111-1111-1111-111111111111";
            ToolAttemptDto settledAttempt =
                    createAttempt(OPERATION_ID, settledAttId, ToolAttemptStatus.SETTLED_SUCCESS);
            when(attemptStore.findLatestAttemptByOperation(WORKSPACE_ID, OPERATION_ID))
                    .thenReturn(Optional.of(settledAttempt));

            List<ReasoningStep> historySettled = new ArrayList<>();
            AgentResult settledResult = handler.handle(ctx, historySettled, msgCtx, stateMachineRunner);

            assertInstanceOf(AgentResult.Finished.class, settledResult);
            verify(toolRegistry, never()).invokeToolWithOperationId(any(), any(), any(), any());
            verify(cpStore)
                    .appendResultIfAbsent(eq(WORKSPACE_ID), eq(502L), anyInt(), any(), eq(List.of(settledAttId)));
            verify(stateMachineRunner).runFinishPass(any(), any(), any());

            // Case C: INDETERMINATE Attempt -> Retains lock, halts replay, does NOT invoke tool
            cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
            String indetAttId = "22222222-2222-2222-2222-222222222222";
            ToolAttemptDto indetAttempt = createAttempt(OPERATION_ID, indetAttId, ToolAttemptStatus.INDETERMINATE);
            when(attemptStore.findLatestAttemptByOperation(WORKSPACE_ID, OPERATION_ID))
                    .thenReturn(Optional.of(indetAttempt));

            List<ReasoningStep> historyIndet = new ArrayList<>();
            AgentResult indetResult = handler.handle(ctx, historyIndet, msgCtx, stateMachineRunner);

            assertInstanceOf(AgentResult.Finished.class, indetResult);
            verify(toolRegistry, never()).invokeToolWithOperationId(any(), any(), any(), any());
            verify(cpStore).appendResultIfAbsent(eq(WORKSPACE_ID), eq(502L), anyInt(), any(), eq(List.of(indetAttId)));
        });
    }

    @Test
    @DisplayName(
            "Cron notification isolation: AwaitingRetry is not published; failures and completions route to cron pipeline")
    void testCronNotificationAndChatIsolation() {
        AgentCronDto cronDto = new AgentCronDto(
                CRON_JOB_ID,
                CRON_SESSION_ID,
                "operator-1",
                "chat-1",
                "telegram",
                "cron-operator",
                "0 0 * * *",
                "UTC",
                false,
                "Daily cron",
                Instant.now(),
                Instant.now(),
                "RUNNING",
                "coordinator",
                null,
                CRON_DELIVERY_ID,
                Instant.now(),
                Instant.now());
        when(cronStore.findById(CRON_JOB_ID)).thenReturn(Optional.of(cronDto));

        // 1. AwaitingRetry must NOT be published as a completed job
        AgentResult retryResult = new AgentResult.AwaitingRetry(CRON_SESSION_ID, 5000, List.of(), 0, 0);
        boolean publishedRetry = cronNotificationDispatcher.dispatch(CRON_SESSION_ID, retryResult);
        assertFalse(publishedRetry, "AwaitingRetry must not be published as completed job");
        verify(domainEventBus, never()).publish(any(), any());
        verify(channelSender, never()).sendRaw(any(), anyString());

        // 2. Completed job routes exclusively through cron notification pipeline
        AgentResult finishedResult = new AgentResult.Finished("Job succeeded", List.of(), 0, 0);
        boolean publishedFinished = cronNotificationDispatcher.dispatch(CRON_SESSION_ID, finishedResult);
        assertTrue(publishedFinished, "Finished cron job must be dispatched to cron pipeline");
        verify(domainEventBus).publish(eq(EventTypeCatalog.AGENT_JOB_COMPLETED), any());
        verify(channelSender, never()).sendRaw(any(), anyString());

        // 3. AwaitingApproval routes through cron event/jobNotifier, NEVER chat ApprovalPrompt
        AgentResult approvalResult = new AgentResult.AwaitingApproval(
                new ApprovalFrame("execute_shell", Map.of("cmd", "ps"), RiskLevel.HIGH),
                CRON_SESSION_ID,
                List.of(),
                0,
                0);
        boolean publishedApproval = cronNotificationDispatcher.dispatch(CRON_SESSION_ID, approvalResult);
        assertTrue(publishedApproval, "AwaitingApproval must be dispatched to cron pipeline");
        verify(channelSender, never()).send(any(), any()); // No ApprovalPrompt sent to chat sender
    }

    private static ToolAttemptDto createAttempt(String opId, String attId, ToolAttemptStatus status) {
        Instant now = Instant.now();
        Instant runAt = (status == ToolAttemptStatus.RUNNING
                        || status == ToolAttemptStatus.SETTLED_SUCCESS
                        || status == ToolAttemptStatus.SETTLED_ERROR
                        || status == ToolAttemptStatus.TIMED_OUT
                        || status == ToolAttemptStatus.INDETERMINATE)
                ? now
                : null;
        return new ToolAttemptDto(
                WORKSPACE_ID,
                CRON_SESSION_ID,
                opId,
                attId,
                1,
                TOOL_NAME,
                FINGERPRINT,
                1,
                null,
                ToolAttemptIdempotencyMode.IDEMPOTENT,
                status,
                0L,
                "BUILT_IN",
                null,
                null,
                null,
                status == ToolAttemptStatus.SETTLED_SUCCESS ? "report data" : null,
                now,
                status != ToolAttemptStatus.ADMITTED ? now : null,
                runAt,
                status.isTerminal() ? now : null);
    }
}
