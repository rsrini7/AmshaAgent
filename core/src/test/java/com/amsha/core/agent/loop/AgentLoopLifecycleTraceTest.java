package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.core.agent.IntentClassificationService;
import com.amsha.core.agent.reasoning.ChatHistoryAssembler;
import com.amsha.core.agent.reasoning.CompactionSummaryCache;
import com.amsha.core.agent.reasoning.LangChainReasoningEngine;
import com.amsha.core.agent.reasoning.ReasoningMessageAssembler;
import com.amsha.core.agent.reasoning.ReasoningStepParser;
import com.amsha.core.agent.reasoning.ReasoningValidator;
import com.amsha.core.agent.reasoning.RecoveryProcessor;
import com.amsha.core.agent.reasoning.StructuredOutputProcessor;
import com.amsha.core.agent.reasoning.SystemPromptBuilder;
import com.amsha.core.agent.reasoning.port.LlmProviderPort;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.agent.sse.AgentSseService;
import com.amsha.core.agent.trace.AgentTraceEvent;
import com.amsha.core.agent.trace.StationCode;
import com.amsha.core.agent.trace.TracePublisher;
import com.amsha.core.agent.trace.TraceStage;
import com.amsha.core.audit.AuditHashUtil;
import com.amsha.core.governance.GovernanceStrategy;
import com.amsha.core.governance.RateLimitTracker;
import com.amsha.core.observability.AgentOtelContext;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.util.TextPatternUtils;
import com.amsha.security.api.AutonomyLevel;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

/**
 * Verifies the per-turn lifecycle trace (turn_start, route, turn_end) emitted by
 * {@link AgentLoop} once per run, in the order required by Issue 02 AC1. These are genuine
 * observed transitions at the turn boundary — no stage is fabricated. Stations without a real
 * execution boundary in the current loop (preflight, memory_decision) are intentionally left to
 * render as {@code NOT_INSTRUMENTED} on the cockpit and must NOT appear here.
 */
class AgentLoopLifecycleTraceTest {

    private LangChainReasoningEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        LlmProviderPort model = mock(LlmProviderPort.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        RoleRegistry roleRegistry = mock(RoleRegistry.class);
        SystemPromptBuilder promptBuilder = mock(SystemPromptBuilder.class);
        ChatHistoryAssembler historyAssembler = mock(ChatHistoryAssembler.class);
        ReasoningStepParser stepParser = mock(ReasoningStepParser.class);
        CompactionSummaryCache summaryCache = mock(CompactionSummaryCache.class);
        AgentOtelContext otelContext = mock(AgentOtelContext.class);
        GovernanceStrategy governanceLayer = mock(GovernanceStrategy.class);
        RateLimitTracker rateLimitTracker = mock(RateLimitTracker.class);
        IntentClassificationService intentClassifier = mock(IntentClassificationService.class);

        when(promptBuilder.build(any(), any(), any(), any(), ArgumentMatchers.anyBoolean()))
                .thenReturn("MOCK SYSTEM PROMPT");

        ReasoningMessageAssembler messageAssembler =
                new ReasoningMessageAssembler(historyAssembler, toolRegistry, new LoopStuckDetector());
        RecoveryProcessor recoveryProcessor = new RecoveryProcessor(new ObjectMapper(), new TextPatternUtils());
        StructuredOutputProcessor structuredOutputProcessor =
                new StructuredOutputProcessor(promptBuilder, model, governanceLayer, otelContext);

        engine = new LangChainReasoningEngine(
                model,
                roleRegistry,
                promptBuilder,
                rateLimitTracker,
                intentClassifier,
                mock(MeterRegistry.class),
                new TextPatternUtils(),
                stepParser,
                messageAssembler,
                recoveryProcessor,
                structuredOutputProcessor,
                new ReasoningValidator(summaryCache),
                null);

        Field otelField = LangChainReasoningEngine.class.getDeclaredField("otelContext");
        otelField.setAccessible(true);
        otelField.set(engine, otelContext);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private AgentLoop wiredLoop(TracePublisher tracePublisher) throws Exception {
        AgentLoop loop = new AgentLoop();

        StatefulAgentStore agentStore = mock(StatefulAgentStore.class);
        AgentStateMachineRunner stateMachineRunner = mock(AgentStateMachineRunner.class);
        CheckpointResumeHandler checkpointResumeHandler = mock(CheckpointResumeHandler.class);
        AuditHashUtil hashUtil = mock(AuditHashUtil.class);
        SessionLifecycleCoordinator sessionLifecycleCoordinator = mock(SessionLifecycleCoordinator.class);
        CheckpointResumeCoordinator checkpointResumeCoordinator = mock(CheckpointResumeCoordinator.class);
        ObservabilityManager observabilityManager = mock(ObservabilityManager.class);
        AgentSseService agentSseService = mock(AgentSseService.class);
        MeterRegistry meterRegistry = mock(MeterRegistry.class);
        Counter counter = mock(Counter.class);
        IntentClassificationService intentClassificationService = mock(IntentClassificationService.class);

        when(sessionLifecycleCoordinator.setupMdc(any(), any())).thenReturn("user");
        when(sessionLifecycleCoordinator.resolveCanonicalRole(any(), any(), any()))
                .thenReturn("coordinator");
        when(checkpointResumeCoordinator.resolveResumePath(any(), any(), any(), any()))
                .thenReturn(new CheckpointResumeCoordinator.ResumeDecision.FreshStart());

        Span span = mock(Span.class);
        Scope scope = mock(Scope.class);
        when(span.makeCurrent()).thenReturn(scope);
        when(observabilityManager.beginSession(any(), any(), any())).thenReturn(span);
        when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(counter);

        setField(loop, "agentStore", agentStore);
        setField(loop, "stateMachineRunner", stateMachineRunner);
        setField(loop, "checkpointResumeHandler", checkpointResumeHandler);
        setField(loop, "hashUtil", hashUtil);
        setField(loop, "sessionLifecycleCoordinator", sessionLifecycleCoordinator);
        setField(loop, "checkpointResumeCoordinator", checkpointResumeCoordinator);
        setField(loop, "observabilityManager", observabilityManager);
        setField(loop, "agentSseService", agentSseService);
        setField(loop, "meterRegistry", meterRegistry);
        setField(loop, "intentClassificationService", intentClassificationService);
        setField(loop, "tracePublisher", tracePublisher);

        return loop;
    }

    private MessageContext telegramContext() {
        ChannelUser user = new ChannelUser("foo", "bar", "disp", ChannelType.TELEGRAM);
        ChannelMessage msg = new ChannelMessage.TelegramMessage(user, "h", "foo", Instant.now());
        return new MessageContext("foo", user, msg);
    }

    @Test
    void successfulTurnEmitsTurnStartPreflightRouteAndTurnEndInOrder() throws Exception {
        TracePublisher tracePublisher = mock(TracePublisher.class);
        AgentLoop loop = wiredLoop(tracePublisher);
        AgentStateMachineRunner stateMachineRunner = mock(AgentStateMachineRunner.class);
        setField(loop, "stateMachineRunner", stateMachineRunner);
        when(stateMachineRunner.run(any(), any(), any())).thenReturn(AgentResult.finished("ok", List.of()));

        WorkspaceContext.run("s1", "user", "chat", AutonomyLevel.FULL, "coordinator", () -> {
            AgentResult result = loop.run("s1", "coordinator", "goal", null, engine, 5, telegramContext());
            assertTrue(result instanceof AgentResult.Finished);
            return null;
        });

        ArgumentCaptor<AgentTraceEvent> captor = ArgumentCaptor.forClass(AgentTraceEvent.class);
        verify(tracePublisher, Mockito.atLeast(4)).publish(captor.capture());
        List<TraceStage> stages =
                captor.getAllValues().stream().map(AgentTraceEvent::stage).toList();

        assertTrue(stages.contains(TraceStage.TURN_START), "expected turn_start, got " + stages);
        assertTrue(stages.contains(TraceStage.PREFLIGHT), "expected preflight, got " + stages);
        assertTrue(stages.contains(TraceStage.ROUTE), "expected route, got " + stages);
        assertTrue(stages.contains(TraceStage.TURN_END), "expected turn_end, got " + stages);

        int iStart = stages.indexOf(TraceStage.TURN_START);
        int iPreflight = stages.indexOf(TraceStage.PREFLIGHT);
        int iRoute = stages.indexOf(TraceStage.ROUTE);
        int iEnd = stages.indexOf(TraceStage.TURN_END);
        assertTrue(iStart < iPreflight, "turn_start must precede preflight");
        assertTrue(iPreflight < iRoute, "preflight must precede route");
        assertTrue(iRoute < iEnd, "route must precede turn_end");

        // preflight event properties
        AgentTraceEvent preflightEvent = captor.getAllValues().stream()
                .filter(e -> e.stage() == TraceStage.PREFLIGHT)
                .findFirst()
                .orElseThrow();
        assertEquals(StationCode.STATION_02_PREFLIGHT, preflightEvent.station());
        assertEquals("PASS", preflightEvent.status());
        assertEquals("fresh_goal", preflightEvent.detail());

        // route detail carries the genuine resolved role
        AgentTraceEvent routeEvent = captor.getAllValues().stream()
                .filter(e -> e.stage() == TraceStage.ROUTE)
                .findFirst()
                .orElseThrow();
        assertEquals("coordinator", routeEvent.detail());
        assertEquals(StationCode.STATION_03_ROUTING, routeEvent.station());
    }

    @Test
    void resumedHistoryEmitsPreflightResumedStatusAndCarriesResumedRoleInRoute() throws Exception {
        TracePublisher tracePublisher = mock(TracePublisher.class);
        AgentLoop loop = wiredLoop(tracePublisher);
        CheckpointResumeCoordinator resumeCoordinator = mock(CheckpointResumeCoordinator.class);
        setField(loop, "checkpointResumeCoordinator", resumeCoordinator);
        when(resumeCoordinator.resolveResumePath(any(), any(), any(), any()))
                .thenReturn(new CheckpointResumeCoordinator.ResumeDecision.ResumedHistory(
                        List.of(), 1, "researcher", null));
        IntentClassificationService intentService = mock(IntentClassificationService.class);
        setField(loop, "intentClassificationService", intentService);
        when(intentService.isResumeCommand(anyString())).thenReturn(true);

        AgentStateMachineRunner stateMachineRunner = mock(AgentStateMachineRunner.class);
        setField(loop, "stateMachineRunner", stateMachineRunner);
        when(stateMachineRunner.run(any(), any(), any())).thenReturn(AgentResult.finished("ok", List.of()));

        WorkspaceContext.run("s1", "user", "chat", AutonomyLevel.FULL, "coordinator", () -> {
            loop.run("s1", "coordinator", "resume task", null, engine, 5, telegramContext());
            return null;
        });

        ArgumentCaptor<AgentTraceEvent> captor = ArgumentCaptor.forClass(AgentTraceEvent.class);
        verify(tracePublisher, Mockito.atLeast(4)).publish(captor.capture());

        AgentTraceEvent preflightEvent = captor.getAllValues().stream()
                .filter(e -> e.stage() == TraceStage.PREFLIGHT)
                .findFirst()
                .orElseThrow();
        assertEquals("RESUMED", preflightEvent.status());
        assertTrue(preflightEvent.detail().contains("resumed_checkpoint_role=researcher"));

        AgentTraceEvent routeEvent = captor.getAllValues().stream()
                .filter(e -> e.stage() == TraceStage.ROUTE)
                .findFirst()
                .orElseThrow();
        assertEquals("researcher", routeEvent.detail());
    }

    @Test
    void memoryDecisionIsNotFabricatedAndRemainsNotInstrumented() throws Exception {
        // The loop has no honest per-turn memory decision boundary; station 04 must remain NOT_INSTRUMENTED.
        TracePublisher tracePublisher = mock(TracePublisher.class);
        AgentLoop loop = wiredLoop(tracePublisher);
        AgentStateMachineRunner stateMachineRunner = mock(AgentStateMachineRunner.class);
        setField(loop, "stateMachineRunner", stateMachineRunner);
        when(stateMachineRunner.run(any(), any(), any())).thenReturn(AgentResult.finished("ok", List.of()));

        WorkspaceContext.run("s1", "user", "chat", AutonomyLevel.FULL, "coordinator", () -> {
            loop.run("s1", "coordinator", "goal", null, engine, 5, telegramContext());
            return null;
        });

        ArgumentCaptor<AgentTraceEvent> captor = ArgumentCaptor.forClass(AgentTraceEvent.class);
        verify(tracePublisher, Mockito.atLeast(1)).publish(captor.capture());
        assertTrue(
                captor.getAllValues().stream().noneMatch(e -> e.stage() == TraceStage.MEMORY_DECISION),
                "memory_decision must never be fabricated");
    }
}
