package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.core.agent.IntentClassificationService;
import com.amsha.core.agent.reasoning.ChatHistoryAssembler;
import com.amsha.core.agent.reasoning.CompactionSummaryCache;
import com.amsha.core.agent.reasoning.LangChainReasoningEngine;
import com.amsha.core.agent.reasoning.ReasoningMessageAssembler;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.reasoning.ReasoningStepParser;
import com.amsha.core.agent.reasoning.ReasoningValidator;
import com.amsha.core.agent.reasoning.RecoveryProcessor;
import com.amsha.core.agent.reasoning.StructuredOutputProcessor;
import com.amsha.core.agent.reasoning.SystemPromptBuilder;
import com.amsha.core.agent.reasoning.port.LlmProviderPort;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.agent.sse.AgentSseService;
import com.amsha.core.audit.AuditHashUtil;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.domain.model.AgentCheckpointRecord;
import com.amsha.core.governance.GovernanceStrategy;
import com.amsha.core.governance.RateLimitTracker;
import com.amsha.core.observability.AgentOtelContext;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.util.TextPatternUtils;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.ToolCapability;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

class AgentLoopTest {

    private LlmProviderPort model;
    private ToolRegistry toolRegistry;
    private RoleRegistry roleRegistry;
    private SystemPromptBuilder promptBuilder;
    private ChatHistoryAssembler historyAssembler;
    private ReasoningStepParser stepParser;
    private LangChainReasoningEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        model = mock(LlmProviderPort.class);
        toolRegistry = mock(ToolRegistry.class);
        roleRegistry = mock(RoleRegistry.class);
        promptBuilder = mock(SystemPromptBuilder.class);
        historyAssembler = mock(ChatHistoryAssembler.class);
        stepParser = mock(ReasoningStepParser.class);
        CompactionSummaryCache summaryCache = mock(CompactionSummaryCache.class);
        AgentOtelContext otelContext = mock(AgentOtelContext.class);
        GovernanceStrategy governanceLayer = mock(GovernanceStrategy.class);
        RateLimitTracker rateLimitTracker = mock(RateLimitTracker.class);
        IntentClassificationService intentClassifier = mock(IntentClassificationService.class);

        when(promptBuilder.build(any(), any(), any(), any(), ArgumentMatchers.anyBoolean()))
                .thenReturn("MOCK SYSTEM PROMPT");

        // Setup Collaborators
        ReasoningMessageAssembler messageAssembler =
                new ReasoningMessageAssembler(historyAssembler, toolRegistry, new LoopStuckDetector());
        RecoveryProcessor recoveryProcessor = new RecoveryProcessor(new ObjectMapper(), new TextPatternUtils());
        ReasoningValidator reasoningValidator = new ReasoningValidator(summaryCache);
        StructuredOutputProcessor structuredOutputProcessor =
                new StructuredOutputProcessor(promptBuilder, model, governanceLayer, otelContext);

        // Instantiate engine using full constructor
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
                reasoningValidator,
                null);

        // Inject otelContext via reflection (field injection, not constructor)
        try {
            var otelField = LangChainReasoningEngine.class.getDeclaredField("otelContext");
            otelField.setAccessible(true);
            otelField.set(engine, otelContext);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject otelContext", e);
        }
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void testAgentLoopDelegatesToStateMachineRunner() {
        // Reflection check to ensure methods moved to the runner are indeed gone from
        // AgentLoop
        Class<AgentLoop> clazz = AgentLoop.class;
        String[] deletedMethods = {
            "runLoop",
            "processEngineStep",
            "validateTool",
            "invokeToolAndObserve",
            "handleFinishAction",
            "saveGoalToChatMemory"
        };

        for (String methodName : deletedMethods) {
            boolean exists = Arrays.stream(clazz.getDeclaredMethods())
                    .anyMatch(m -> m.getName().equals(methodName));
            assertFalse(exists, "Method " + methodName + " should have been deleted from AgentLoop");
        }

        // Also check if the injection is there
        boolean hasRunner = Arrays.stream(clazz.getDeclaredFields())
                .anyMatch(f -> f.getName().equals("stateMachineRunner"));
        assertTrue(hasRunner, "AgentLoop should have stateMachineRunner injected");
    }

    @Test
    void channelStampFailureDoesNotCorruptAwaitingApprovalResult() throws Exception {
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
        when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(counter);

        IntentClassificationService intentClassificationService = mock(IntentClassificationService.class);

        when(sessionLifecycleCoordinator.setupMdc(any(), any())).thenReturn("user");
        when(checkpointResumeCoordinator.resolveResumePath(any(), any(), any(), any()))
                .thenReturn(new CheckpointResumeCoordinator.ResumeDecision.FreshStart());

        setField(loop, "agentStore", agentStore);
        setField(loop, "stateMachineRunner", stateMachineRunner);
        setField(loop, "checkpointResumeHandler", checkpointResumeHandler);
        setField(loop, "hashUtil", hashUtil);
        setField(loop, "sessionLifecycleCoordinator", sessionLifecycleCoordinator);
        Span span = mock(Span.class);
        Scope scope = mock(Scope.class);
        when(span.makeCurrent()).thenReturn(scope);
        when(observabilityManager.beginSession(any(), any(), any())).thenReturn(span);

        setField(loop, "checkpointResumeCoordinator", checkpointResumeCoordinator);
        setField(loop, "observabilityManager", observabilityManager);
        setField(loop, "agentSseService", agentSseService);
        setField(loop, "meterRegistry", meterRegistry);
        setField(loop, "intentClassificationService", intentClassificationService);

        ChannelUser user = new ChannelUser("foo", "bar", "disp", ChannelType.TELEGRAM);
        ChannelMessage msg = new ChannelMessage.TelegramMessage(user, "h", "foo", Instant.now());
        MessageContext ctx = new MessageContext("foo", user, msg);

        AgentCheckpoint checkpoint = mock(AgentCheckpointRecord.class);
        when(checkpoint.getStatus()).thenReturn(CheckpointStatus.IN_PROGRESS);

        when(agentStore.load(any(), any())).thenReturn(Optional.of(checkpoint));
        when(agentStore.save(any())).thenThrow(new RuntimeException("SQLITE_BUSY"));

        AgentResult approval = AgentResult.awaitingApproval("mytool", "{}", "s1", Collections.emptyList());
        when(stateMachineRunner.run(any(), any(), any())).thenReturn(approval);

        WorkspaceContext.run("s1", "user", "chat", AutonomyLevel.FULL, "coordinator", () -> {
            AgentResult result = loop.runWithRole("s1", "coord", "goal", null, engine, 5, ctx);

            assertTrue(result instanceof AgentResult.AwaitingApproval);
            return null;
        });
    }

    @Test
    void continuationCommandRestoresHistoryFromCompletedCheckpoint() throws Exception {
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
        when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(counter);

        IntentClassificationService intentClassificationService = mock(IntentClassificationService.class);

        ReasoningStep priorStep = ReasoningStep.fastPath(
                "prior work", ToolCapability.HOST_EXEC.toolName(), Map.of("code", "cat viewer.html"));
        when(checkpointResumeCoordinator.resolveResumePath(any(), any(), any(), any()))
                .thenReturn(new CheckpointResumeCoordinator.ResumeDecision.ResumedHistory(
                        List.of(priorStep), 1, "skill:algorithmic-art", null));
        when(intentClassificationService.isContinuationCommand("proceed")).thenReturn(true);
        when(intentClassificationService.isResumeCommand(anyString())).thenReturn(false);

        when(sessionLifecycleCoordinator.setupMdc(any(), any())).thenReturn("user");

        setField(loop, "agentStore", agentStore);
        setField(loop, "stateMachineRunner", stateMachineRunner);
        setField(loop, "checkpointResumeHandler", checkpointResumeHandler);
        setField(loop, "hashUtil", hashUtil);
        setField(loop, "sessionLifecycleCoordinator", sessionLifecycleCoordinator);
        Span span = mock(Span.class);
        Scope scope = mock(Scope.class);
        when(span.makeCurrent()).thenReturn(scope);
        when(observabilityManager.beginSession(any(), any(), any())).thenReturn(span);

        setField(loop, "checkpointResumeCoordinator", checkpointResumeCoordinator);
        setField(loop, "observabilityManager", observabilityManager);
        setField(loop, "agentSseService", agentSseService);
        setField(loop, "meterRegistry", meterRegistry);
        setField(loop, "intentClassificationService", intentClassificationService);

        ChannelUser user = new ChannelUser("foo", "bar", "disp", ChannelType.TELEGRAM);
        ChannelMessage msg = new ChannelMessage.TelegramMessage(user, "proceed", "foo", Instant.now());
        MessageContext ctx = new MessageContext("foo", user, msg);

        AgentResult finished = AgentResult.finished("ok", List.of());
        when(stateMachineRunner.run(any(), any(), any())).thenReturn(finished);

        WorkspaceContext.run("s1", "user", "chat", AutonomyLevel.FULL, "skill:algorithmic-art", () -> {
            AgentResult result = loop.runWithRole("s1", "skill:algorithmic-art", "original goal", null, engine, 5, ctx);

            assertTrue(result instanceof AgentResult.Finished);
            return null;
        });
    }

    @Test
    void resumeCommandAsGoalRestoresOriginalGoalFromCheckpoint() throws Exception {
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
        when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(counter);

        IntentClassificationService intentClassificationService = mock(IntentClassificationService.class);

        // Mock resume command detection
        when(intentClassificationService.isResumeCommand("yes proceed")).thenReturn(true);
        when(intentClassificationService.isResumeCommand("original goal")).thenReturn(false);

        // Mock checkpoint with original goal
        AgentCheckpoint checkpoint = mock(AgentCheckpointRecord.class);
        when(checkpoint.getOriginalGoal()).thenReturn("original goal");
        when(agentStore.findLatestNonArchivedCheckpoint("s1")).thenReturn(Optional.of(checkpoint));

        when(sessionLifecycleCoordinator.setupMdc(any(), any())).thenReturn("user");
        when(checkpointResumeCoordinator.resolveResumePath(any(), any(), any(), any()))
                .thenReturn(new CheckpointResumeCoordinator.ResumeDecision.FreshStart());

        setField(loop, "agentStore", agentStore);
        setField(loop, "stateMachineRunner", stateMachineRunner);
        setField(loop, "checkpointResumeHandler", checkpointResumeHandler);
        setField(loop, "hashUtil", hashUtil);
        setField(loop, "sessionLifecycleCoordinator", sessionLifecycleCoordinator);
        Span span = mock(Span.class);
        Scope scope = mock(Scope.class);
        when(span.makeCurrent()).thenReturn(scope);
        when(observabilityManager.beginSession(any(), any(), any())).thenReturn(span);

        setField(loop, "checkpointResumeCoordinator", checkpointResumeCoordinator);
        setField(loop, "observabilityManager", observabilityManager);
        setField(loop, "agentSseService", agentSseService);
        setField(loop, "meterRegistry", meterRegistry);
        setField(loop, "intentClassificationService", intentClassificationService);

        ChannelUser user = new ChannelUser("foo", "bar", "disp", ChannelType.TELEGRAM);
        ChannelMessage msg = new ChannelMessage.TelegramMessage(user, "yes proceed", "foo", Instant.now());
        MessageContext ctx = new MessageContext("foo", user, msg);

        AgentResult finished = AgentResult.finished("ok", List.of());
        ArgumentCaptor<AgentExecutionContext> contextCaptor = ArgumentCaptor.forClass(AgentExecutionContext.class);
        when(stateMachineRunner.run(contextCaptor.capture(), any(), any())).thenReturn(finished);

        WorkspaceContext.run("s1", "user", "chat", AutonomyLevel.FULL, "coordinator", () -> {
            AgentResult result = loop.runWithRole("s1", "coordinator", "yes proceed", null, engine, 5, ctx);

            assertTrue(result instanceof AgentResult.Finished);
            assertEquals("original goal", contextCaptor.getValue().goal());
            return null;
        });
    }
}
