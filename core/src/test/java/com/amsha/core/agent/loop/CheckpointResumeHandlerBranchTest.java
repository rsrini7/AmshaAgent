package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.channel.api.ChannelPlugin;
import com.amsha.channel.api.ChannelSender;
import com.amsha.channel.api.ChannelUser;
import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.channel.ChannelPluginRegistry;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.speech.SpeechDeliveryService;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.persistence.api.CronStorePort;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.AgentSentinels;
import com.amsha.tool.api.ToolCapability;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.ManagedContext;

/**
 * Branch coverage for the two resume entry points and their delegation hand-off.
 *
 * <p>The async entry point dispatches a lambda to {@link ManagedExecutor}. {@link CheckpointResumeHandlerUnitTest}
 * only ever exercises the code <em>around</em> that dispatch, so the whole task body was dark. Here the executor
 * captures the {@link Runnable} and the test drives it inline.
 *
 * <p><strong>{@code Arc} must be stubbed.</strong> {@code resumeAfterBatchDecision} opens with
 * {@code AgentGuards.isShuttingDown()} ({@code Arc.container() != null && !isRunning()}) and the task body calls
 * {@code Arc.container().requestContext().activate()}. Outside Quarkus {@code Arc.container()} is {@code null},
 * so without the static mock the shutdown guard is permanently false and the task body NPEs on its first line.
 * {@link WorkspaceScopeHolder} reads the same static, so the stub must stay open while the task runs — the
 * container's {@code requestContext()} is a mock whose {@code activate()}/{@code deactivate()} are no-ops.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CheckpointResumeHandlerBranchTest {

    @Mock
    StatefulAgentStore agentStore;

    @Mock
    ToolRegistry toolRegistry;

    @Mock
    AgentLoop agentLoop;

    @Mock
    ReasoningEngine reasoningEngine;

    @Mock
    ChannelPluginRegistry channelPluginRegistry;

    @Mock
    CronStorePort cronStore;

    @Mock
    CliResultBridge cliResultBridge;

    @Mock
    ExecutionFailureRecoveryService recoveryService;

    @Mock
    PersonaEscalationService escalationService;

    @Mock
    ManagedExecutor executor;

    @Mock
    SpeechDeliveryService speechDeliveryService;

    @Mock
    MeterRegistry meterRegistry;

    @Mock
    Counter resumeFailureCounter;

    CheckpointResumeHandler handler;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** The task handed to the executor by the most recent {@code resumeAfterBatchDecision} call. */
    private final AtomicReference<Runnable> dispatched = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        dispatched.set(null);
        handler = new CheckpointResumeHandler(
                agentStore,
                toolRegistry,
                objectMapper,
                agentLoop,
                reasoningEngine,
                channelPluginRegistry,
                cronStore,
                cliResultBridge,
                recoveryService,
                escalationService);
        handler.executor = executor;
        handler.speechDeliveryService = speechDeliveryService;
        handler.meterRegistry = meterRegistry;
        when(meterRegistry.counter(anyString())).thenReturn(resumeFailureCounter);
        // Capture rather than run: the test decides when the task body executes.
        doAnswer(invocation -> {
                    dispatched.set(invocation.getArgument(0, Runnable.class));
                    return null;
                })
                .when(executor)
                .execute(any(Runnable.class));
    }

    // ===================== helpers =====================

    /** A checkpoint shaped like one left behind by a paused session. */
    private static AgentCheckpoint resumeCheckpoint(String sessionId) {
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getSessionId()).thenReturn(sessionId);
        when(cp.getAgentRole()).thenReturn("r1");
        when(cp.getOriginalGoal()).thenReturn("resume goal");
        when(cp.getSystemNote()).thenReturn("note");
        when(cp.getMaxIterations()).thenReturn(10);
        when(cp.getIterationsUsed()).thenReturn(2);
        when(cp.getAutonomyLevel()).thenReturn(AutonomyLevel.SUPERVISED);
        when(cp.getPendingToolName()).thenReturn(null);
        return cp;
    }

    /** Registers a plugin whose sender is returned, so the test can verify delivery. */
    private ChannelSender senderFor(String channelId) {
        ChannelSender sender = mock(ChannelSender.class);
        ChannelPlugin plugin = mock(ChannelPlugin.class);
        when(plugin.sender()).thenReturn(sender);
        when(channelPluginRegistry.find(channelId)).thenReturn(Optional.of(plugin));
        return sender;
    }

    /** Runs {@code body} with {@code Arc.container()} stubbed to a <em>running</em> container. */
    private void withRunningArc(ManagedContext requestContext, Runnable body) {
        try (MockedStatic<Arc> arc = Mockito.mockStatic(Arc.class)) {
            ArcContainer container = mock(ArcContainer.class);
            when(container.isRunning()).thenReturn(true);
            when(container.requestContext()).thenReturn(requestContext);
            arc.when(Arc::container).thenReturn(container);
            body.run();
        }
    }

    /** Runs {@code body} with {@code Arc.container()} stubbed to a container that is shutting down. */
    private void withStoppingArc(Runnable body) {
        try (MockedStatic<Arc> arc = Mockito.mockStatic(Arc.class)) {
            ArcContainer container = mock(ArcContainer.class);
            when(container.isRunning()).thenReturn(false);
            arc.when(Arc::container).thenReturn(container);
            body.run();
        }
    }

    // ===================== shutdown guard =====================

    @Test
    void aShuttingDownContainerShortCircuitsBeforeAnyStateIsTouched() {
        withStoppingArc(() -> assertFalse(handler.resumeAfterBatchDecision("s-shutdown", "r1", "u1", "c1", "cli")));

        verifyNoInteractions(agentStore);
        verifyNoInteractions(executor);
    }

    // ===================== async task body =====================

    @Test
    void asyncResumeRunsTheDispatchedTaskAndDeliversTheResultOverTheChannel() {
        AgentCheckpoint checkpoint = resumeCheckpoint("s-async");
        ChannelSender sender = senderFor("telegram");
        AgentResult expected = AgentResult.finished("done", List.of());
        when(agentStore.claimForResume("s-async", "r1")).thenReturn(true);
        doReturn(Optional.of(checkpoint)).when(agentStore).load("s-async", "r1");
        when(agentLoop.run(any(), any(), any(), any(), any(), anyInt(), any())).thenReturn(expected);

        ManagedContext requestContext = mock(ManagedContext.class);
        withRunningArc(requestContext, () -> {
            assertTrue(handler.resumeAfterBatchDecision("s-async", "r1", "u1", "c1", "telegram"));

            Runnable task = dispatched.get();
            assertNotNull(task, "the resume task must reach the executor");
            task.run();
        });

        // Opened by the task body, closed by its finally block.
        verify(requestContext, atLeastOnce()).activate();
        verify(requestContext, atLeastOnce()).deactivate();

        verify(speechDeliveryService).deliver(any(), eq(sender), any(ChannelUser.class), eq(expected), anyString());
        verify(agentStore).releaseResumeClaim("s-async", "r1");
        verify(agentStore).clearBatchResuming("s-async");
    }

    @Test
    void asyncResumeDefaultsToWebsocketWhenNoChannelTypeIsSupplied() {
        AgentCheckpoint checkpoint = resumeCheckpoint("s-ws");
        ChannelSender sender = senderFor("websocket");
        AgentResult expected = AgentResult.finished("done", List.of());
        when(agentStore.claimForResume("s-ws", "r1")).thenReturn(true);
        doReturn(Optional.of(checkpoint)).when(agentStore).load("s-ws", "r1");
        when(agentLoop.run(any(), any(), any(), any(), any(), anyInt(), any())).thenReturn(expected);

        ManagedContext requestContext = mock(ManagedContext.class);
        withRunningArc(requestContext, () -> {
            assertTrue(handler.resumeAfterBatchDecision("s-ws", "r1", "u1", "c1", null));
            dispatched.get().run();
        });

        verify(speechDeliveryService).deliver(any(), eq(sender), any(ChannelUser.class), eq(expected), anyString());
    }

    @Test
    void asyncResumeReportsALoopFailureAndStillReleasesTheClaim() {
        AgentCheckpoint checkpoint = resumeCheckpoint("s-fail");
        when(agentStore.claimForResume("s-fail", "r1")).thenReturn(true);
        doReturn(Optional.of(checkpoint)).when(agentStore).load("s-fail", "r1");
        when(agentLoop.run(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenThrow(new IllegalStateException("loop blew up"));
        when(channelPluginRegistry.find("telegram")).thenReturn(Optional.empty());

        ManagedContext requestContext = mock(ManagedContext.class);
        withRunningArc(requestContext, () -> {
            assertTrue(handler.resumeAfterBatchDecision("s-fail", "r1", "u1", "c1", "telegram"));
            dispatched.get().run();
        });

        // Reported once by runResumeLoop's own handler and once by the task body's outer catch.
        verify(resumeFailureCounter, times(2)).increment();
        verify(agentStore).releaseResumeClaim("s-fail", "r1");
        verifyNoInteractions(speechDeliveryService);
    }

    @Test
    void asyncResumeToleratesARequestContextDeactivationFailure() {
        AgentCheckpoint checkpoint = resumeCheckpoint("s-deact");
        when(agentStore.claimForResume("s-deact", "r1")).thenReturn(true);
        doReturn(Optional.of(checkpoint)).when(agentStore).load("s-deact", "r1");
        when(agentLoop.run(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(AgentResult.finished("done", List.of()));
        when(channelPluginRegistry.find("telegram")).thenReturn(Optional.empty());

        ManagedContext requestContext = mock(ManagedContext.class);
        // A failing deactivate must be swallowed, both by WorkspaceScopeHolder and by the task's finally.
        doThrow(new IllegalStateException("deactivate blew up"))
                .when(requestContext)
                .deactivate();

        withRunningArc(requestContext, () -> {
            assertTrue(handler.resumeAfterBatchDecision("s-deact", "r1", "u1", "c1", "telegram"));
            dispatched.get().run();
        });

        verify(agentStore).releaseResumeClaim("s-deact", "r1");
        verify(agentStore).clearBatchResuming("s-deact");
        verifyNoInteractions(speechDeliveryService);
    }

    // ===================== delegation hand-off (async) =====================

    @Test
    void asyncResumeResumesTheParentWhenADelegatedChildCompletes() {
        String childSession = "parent-s" + AgentSentinels.DELEGATE_SESSION_SENTINEL + "garuda:child";
        AgentCheckpoint child = resumeCheckpoint(childSession);
        AgentCheckpoint parent = sequentialDelegatingParent("parent-s");

        when(agentStore.claimForResume(childSession, "r1")).thenReturn(true);
        when(agentStore.claimForResume("parent-s", "parent-role")).thenReturn(true);
        doReturn(Optional.of(child)).when(agentStore).load(childSession, "r1");
        doReturn(Optional.of(parent)).when(agentStore).load("parent-s", "parent-role");
        when(agentStore.findLatestAwaitingApproval("parent-s")).thenReturn(Optional.of(parent));
        when(agentStore.deserializeSteps(parent)).thenReturn(null);
        when(agentLoop.run(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(AgentResult.finished("child done", List.of()));

        ManagedContext requestContext = mock(ManagedContext.class);
        withRunningArc(requestContext, () -> {
            assertTrue(handler.resumeAfterBatchDecision(childSession, "r1", "u1", "c1", "cli"));
            dispatched.get().run();
        });

        // The child's own result is never dispatched: the parent is resumed instead.
        verify(agentStore).claimForResume("parent-s", "parent-role");
        verify(agentStore).releaseResumeClaim(childSession, "r1");
        verifyNoInteractions(speechDeliveryService);
    }

    /** A parent awaiting approval of a single sequential {@code garuda} delegation. */
    private AgentCheckpoint sequentialDelegatingParent(String parentSession) {
        AgentCheckpoint parent = mock(AgentCheckpoint.class);
        when(parent.getSessionId()).thenReturn(parentSession);
        when(parent.getAgentRole()).thenReturn("parent-role");
        when(parent.getChannelPlatformId()).thenReturn("u1");
        when(parent.getContextDataJson()).thenReturn(null);
        when(parent.getChannelType()).thenReturn("telegram");
        when(parent.isParallelBatch()).thenReturn(false);
        when(parent.getPendingToolName()).thenReturn(ToolCapability.DELEGATE_TO_AGENT.toolName());
        return parent;
    }

    // ===================== delegation hand-off (sync) =====================

    @Test
    void syncResumeResumesTheParentWhenADelegatedChildCompletes() throws Exception {
        String childSession = "parent-s2" + AgentSentinels.DELEGATE_SESSION_SENTINEL + "garuda:child";
        AgentCheckpoint child = resumeCheckpoint(childSession);
        AgentCheckpoint parent = sequentialDelegatingParent("parent-s2");
        when(parent.getOriginalGoal()).thenReturn("parent goal");

        AgentResult childResult = AgentResult.finished("child done", List.of());
        AgentResult parentResult = AgentResult.finished("parent done", List.of());

        when(agentStore.claimForResume(childSession, "r1")).thenReturn(true);
        when(agentStore.claimForResume("parent-s2", "parent-role")).thenReturn(true);
        doReturn(Optional.of(child)).when(agentStore).load(childSession, "r1");
        doReturn(Optional.of(parent)).when(agentStore).load("parent-s2", "parent-role");
        when(agentStore.findLatestAwaitingApproval("parent-s2")).thenReturn(Optional.of(parent));
        when(agentStore.deserializeSteps(parent)).thenReturn(null);
        when(agentLoop.run(eq(childSession), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(childResult);
        when(agentLoop.run(eq("parent-s2"), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(parentResult);

        AgentResult actual = handler.resumeAfterBatchDecisionSync(childSession, "r1", "u1", "c1", "cli");

        // The parent's run is what the caller sees; the child's result was folded into the parent batch.
        assertSame(parentResult, actual);
        // Released once before the parent is resumed and once by the outer finally.
        verify(agentStore, times(2)).releaseResumeClaim(childSession, "r1");
    }

    @Test
    void syncResumeKeepsWaitingWhenTheDelegatedBatchStillHasActiveChildren() throws Exception {
        String childSession = "parent-p" + AgentSentinels.DELEGATE_SESSION_SENTINEL + "garuda:child";
        AgentCheckpoint child = resumeCheckpoint(childSession);

        AgentCheckpoint parent = mock(AgentCheckpoint.class);
        when(parent.getSessionId()).thenReturn("parent-p");
        when(parent.getAgentRole()).thenReturn("parent-role");
        when(parent.getChannelPlatformId()).thenReturn("u1");
        when(parent.getContextDataJson()).thenReturn(null);
        when(parent.isParallelBatch()).thenReturn(true);
        when(parent.getPendingToolName()).thenReturn(null);
        when(parent.getPendingToolParametersJson())
                .thenReturn(objectMapper.writeValueAsString(List.of(ParallelToolCall.pending(
                        "call_0", ToolCapability.DELEGATE_TO_AGENT.toolName(), Map.of("agent", "garuda"), "{}"))));
        when(parent.getPreExecutedResults()).thenReturn(null);

        // A sibling delegate for the same parent is still in flight, so the parent is not ready.
        AgentCheckpoint sibling = mock(AgentCheckpoint.class);
        when(sibling.getSessionId()).thenReturn("parent-p" + AgentSentinels.DELEGATE_SESSION_SENTINEL + "garuda:other");
        when(agentStore.findByStatus(any())).thenReturn(List.of(sibling));

        AgentResult childResult = AgentResult.finished("child done", List.of());
        when(agentStore.claimForResume(childSession, "r1")).thenReturn(true);
        doReturn(Optional.of(child)).when(agentStore).load(childSession, "r1");
        when(agentStore.findLatestAwaitingApproval("parent-p")).thenReturn(Optional.of(parent));
        when(agentLoop.run(any(), any(), any(), any(), any(), anyInt(), any())).thenReturn(childResult);

        AgentResult actual = handler.resumeAfterBatchDecisionSync(childSession, "r1", "u1", "c1", "cli");

        // The batch result was recorded on the parent, but the parent itself must not be resumed yet.
        assertSame(childResult, actual);
        verify(agentStore).save(parent);
        verify(agentStore, never()).claimForResume("parent-p", "parent-role");
    }
}
