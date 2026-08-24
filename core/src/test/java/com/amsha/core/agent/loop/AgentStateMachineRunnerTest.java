package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InvalidRequestException;

import jakarta.enterprise.inject.Instance;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

import com.amsha.core.agent.IntentClassificationService;
import com.amsha.core.agent.reasoning.LangChainReasoningEngine;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.agent.sse.AgentSseService;
import com.amsha.core.domain.model.AgentCheckpointRecord;
import com.amsha.core.governance.ApprovalFrame;
import com.amsha.core.governance.BudgetExceededException;
import com.amsha.core.governance.GovernanceStrategy;
import com.amsha.core.governance.RateLimitTracker;
import com.amsha.core.observability.AgentObserver;
import com.amsha.core.observability.AgentOtelContext;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.security.DestructiveVocabularyFilter;
import com.amsha.core.security.InputSanitizer;
import com.amsha.core.skill.SkillRegistry;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.tool.DynamicToolLookup;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.util.TextPatternUtils;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.RiskLevel;
import com.amsha.tool.api.AgentTool;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.trace.Span;

@ExtendWith(MockitoExtension.class)
class AgentStateMachineRunnerTest {

    private AgentStateMachineRunner runner;
    private ReasoningEngine engine;
    private ToolRegistry toolRegistry;
    private StatefulAgentStore agentStore;
    private GovernanceStrategy governanceLayer;
    private ToolInvocationGuard guard;
    private ToolInvoker invoker;
    private ChatMemoryWriter writer;
    private SkillRegistry skillRegistry;
    private InputSanitizer piiScanReflex;
    private AgentOtelContext agentOtelContext;
    private IntentClassificationService intentDetector;
    private ObjectMapper objectMapper;

    @SuppressWarnings("unchecked")
    private Instance<DynamicToolLookup> dynamicToolLookup = mock(Instance.class);

    @SuppressWarnings("unchecked")
    private Instance<AgentObserver> observers = mock(Instance.class);

    private DestructiveVocabularyFilter destructiveFilter = mock(DestructiveVocabularyFilter.class);

    private FinishActionHandler finishActionHandler;
    private AnswerGuardStrategy qualityGuard;
    private CodeGenerationPolicy codePolicy;
    private CorrectionStrategy correctionStrategy;
    private LoopExitPublisher loopExitPublisher = mock(LoopExitPublisher.class);
    private ParallelToolStepExecutor parallelExecutor = mock(ParallelToolStepExecutor.class);
    private RateLimitTracker rateLimitTracker = mock(RateLimitTracker.class);
    private PiiRedactionTransformer piiTransformer = mock(PiiRedactionTransformer.class);

    @Mock
    AgentSseService agentSseService;

    @BeforeEach
    void setUp() {
        engine = mock(LangChainReasoningEngine.class);
        toolRegistry = mock(ToolRegistry.class);
        agentStore = mock(StatefulAgentStore.class);
        governanceLayer = mock(GovernanceStrategy.class);
        guard = mock(ToolInvocationGuard.class);
        invoker = mock(ToolInvoker.class);
        writer = mock(ChatMemoryWriter.class);
        skillRegistry = mock(SkillRegistry.class);
        piiScanReflex = mock(InputSanitizer.class);
        agentOtelContext = mock(AgentOtelContext.class);
        intentDetector = mock(IntentClassificationService.class);

        qualityGuard = mock(AnswerGuardStrategy.class);
        codePolicy = mock(CodeGenerationPolicy.class);
        correctionStrategy = mock(CorrectionStrategy.class);
        objectMapper = mock(ObjectMapper.class);
        TextPatternUtils textPatternUtils = new TextPatternUtils();

        ToolRegistry toolRegistryForHandler = mock(ToolRegistry.class);
        Mockito.lenient()
                .when(toolRegistryForHandler.listTools())
                .thenReturn(List.of(
                        ToolCapability.SANDBOX_EXEC.toolName(),
                        ToolCapability.WEB_SEARCH_SERPER.toolName(),
                        ToolCapability.RECALL_MEMORY_SEARCH.toolName()));
        RoleRegistry roleRegistryMock = mock(RoleRegistry.class);
        Mockito.lenient().when(roleRegistryMock.permittedTools(any())).thenAnswer(invocation -> {
            AgentRoleEnum r = invocation.getArgument(0);
            if ("coordinator".equals(r.id())) {
                return Set.of(ToolCapability.SCHEDULE_TASK.toolName(), ToolCapability.RECALL_MEMORY_SEARCH.toolName());
            } else if ("coder".equals(r.id())) {
                return Set.of(ToolCapability.SANDBOX_EXEC.toolName());
            }
            return Set.of();
        });
        finishActionHandler = new FinishActionHandler(
                qualityGuard,
                codePolicy,
                rateLimitTracker,
                observers,
                correctionStrategy,
                objectMapper,
                textPatternUtils,
                toolRegistryForHandler,
                roleRegistryMock);

        ApprovalCheckpointManager checkpointManager =
                new ApprovalCheckpointManager(agentStore, agentSseService, new ObjectMapper());

        LlmExceptionRouter exceptionRouter = new LlmExceptionRouter(new ObjectMapper());

        EngineStepHandler engineStepHandler = new EngineStepHandler(
                governanceLayer,
                observers,
                agentStore,
                new ResponseRecoveryHintBuilder(),
                exceptionRouter,
                4,
                6,
                15,
                10);

        ToolStepHandler toolStepHandler = new ToolStepHandler(
                observers,
                guard,
                invoker,
                writer,
                checkpointManager,
                parallelExecutor,
                new SimpleMeterRegistry(),
                mock(ExecutionFailureRecoveryService.class),
                new ToolStepValidator(toolRegistry, observers, dynamicToolLookup),
                mock(ToolStepEscalationHandler.class));

        StepDecisionRouter stepDecisionRouter =
                new StepDecisionRouter(finishActionHandler, toolStepHandler, writer, engineStepHandler);

        BrowserPreflightHandler browserPreflightHandler = mock(BrowserPreflightHandler.class);

        runner = new AgentStateMachineRunner(
                engineStepHandler,
                writer,
                observers,
                skillRegistry,
                agentOtelContext,
                intentDetector,
                loopExitPublisher,
                finishActionHandler,
                correctionStrategy,
                3,
                new SimpleMeterRegistry(),
                stepDecisionRouter,
                browserPreflightHandler);

        lenient().when(observers.iterator()).thenReturn(Collections.emptyIterator());
        lenient().when(skillRegistry.currentSnapshot()).thenReturn(Collections.emptyList());
        lenient().when(intentDetector.isWriteIntent(anyString())).thenReturn(false);

        Span invalidSpan = Span.getInvalid();
        lenient()
                .when(agentOtelContext.startIterationSpan(anyString(), anyInt(), anyString()))
                .thenReturn(invalidSpan);
        lenient()
                .when(agentOtelContext.startToolSpan(anyString(), anyString(), anyString()))
                .thenReturn(invalidSpan);

        lenient().when(dynamicToolLookup.isUnsatisfied()).thenReturn(true);

        lenient().when(destructiveFilter.isDestructive(anyString())).thenReturn(false);
        lenient().when(destructiveFilter.filter(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));

        lenient()
                .when(qualityGuard.guard(any(), any()))
                .thenAnswer(inv -> new AnswerGuardStrategy.AnswerGuardResult(inv.getArgument(0), false, null));

        // Fix NPE: stub sanitizers to return input by default
        lenient().when(piiTransformer.transform(any(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(piiScanReflex.sanitize(anyString())).thenAnswer(inv -> inv.getArgument(0));

        lenient().when(agentStore.saveWithSteps(any(), anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void testFinishOnFirstStep() {
        ReasoningStep finishStep =
                ReasoningStep.pending("done", ReasoningStep.FINISH, Map.of("answer", "Hello"), 100, 100);
        when(engine.nextStep(any(), anyString(), anyString(), anyList())).thenReturn(finishStep);

        AgentExecutionContext ctx = new AgentExecutionContext(
                "s1",
                "u1",
                "c1",
                "coordinator",
                "goal",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                5,
                0,
                null,
                null,
                null,
                null);
        AgentResult result = runner.run(ctx, engine, List.of());

        assertInstanceOf(AgentResult.Finished.class, result);
        assertEquals("Hello", ((AgentResult.Finished) result).answer());
    }

    @Test
    void testLoopExceededAfterMaxIterations() {
        ReasoningStep toolStep = ReasoningStep.pending("thought", "tool", Map.of(), 100, 100);
        when(engine.nextStep(any(), anyString(), anyString(), anyList())).thenReturn(toolStep);
        when(toolRegistry.findTool(anyString())).thenReturn(Optional.of(mock(AgentTool.class)));
        when(guard.evaluate(anyString(), anyString(), any(), anyString(), anyMap(), anyList(), anyString(), anyList()))
                .thenReturn(new ToolInvocationGuard.GuardDecision.Allow());
        when(invoker.invoke(anyString(), anyMap(), anyString())).thenReturn(ToolResponse.success("output"));

        AgentExecutionContext ctx = new AgentExecutionContext(
                "s1",
                "u1",
                "c1",
                "coordinator",
                "goal",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                2,
                0,
                null,
                null,
                null,
                null);
        AgentResult result = runner.run(ctx, engine, List.of());

        assertInstanceOf(AgentResult.LoopExceeded.class, result);
        assertEquals(2, ((AgentResult.LoopExceeded) result).maxIterations());
    }

    @Test
    void testHallucinatedToolIntercepted() {
        ReasoningStep hallucinatedStep = ReasoningStep.pending("thought", "fake-tool", Map.of(), 100, 100);
        ReasoningStep finishStep =
                ReasoningStep.pending("done", ReasoningStep.FINISH, Map.of("answer", "fixed"), 100, 100);

        when(engine.nextStep(any(), anyString(), anyString(), anyList()))
                .thenReturn(hallucinatedStep)
                .thenReturn(finishStep);
        when(toolRegistry.findTool("fake-tool")).thenReturn(Optional.empty());
        when(toolRegistry.listTools()).thenReturn(List.of("valid-tool"));

        AgentExecutionContext ctx = new AgentExecutionContext(
                "s1",
                "u1",
                "c1",
                "coordinator",
                "goal",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                5,
                0,
                null,
                null,
                null,
                null);
        AgentResult result = runner.run(ctx, engine, List.of());

        assertInstanceOf(AgentResult.Finished.class, result);
        assertEquals("fixed", ((AgentResult.Finished) result).answer());
    }

    @Test
    void testBudgetExceededAborts() throws Exception {
        ReasoningStep toolStep = ReasoningStep.pending("thought", "tool", Map.of(), 100, 100);
        when(engine.nextStep(any(), anyString(), anyString(), anyList())).thenReturn(toolStep);
        doThrow(new BudgetExceededException("Budget blown"))
                .when(governanceLayer)
                .recordLlmCall(anyString(), anyInt(), anyInt());

        AgentExecutionContext ctx = new AgentExecutionContext(
                "s1",
                "u1",
                "c1",
                "coordinator",
                "goal",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                5,
                0,
                null,
                null,
                null,
                null);
        AgentResult result = runner.run(ctx, engine, List.of());

        assertInstanceOf(AgentResult.Failed.class, result);
        assertEquals(AgentResult.FailureCategory.BUDGET_EXCEEDED, ((AgentResult.Failed) result).category());
        assertTrue(((AgentResult.Failed) result).reason().contains("Budget blown"));
    }

    @Test
    void testAwaitingApprovalPausesLoop() throws Exception {
        // FIX #10: uses String "telegram" instead of ChannelType.TELEGRAM
        WorkspaceContext.run("s1", "u1", "c1", AutonomyLevel.SUPERVISED, "coordinator", "telegram", (Callable<Void>)
                () -> {
                    ReasoningStep toolStep = ReasoningStep.pending("thought", "protected-tool", Map.of(), 100, 100);
                    when(engine.nextStep(any(), anyString(), anyString(), anyList()))
                            .thenReturn(toolStep);
                    when(toolRegistry.findTool("protected-tool")).thenReturn(Optional.of(mock(AgentTool.class)));
                    when(guard.evaluate(
                                    anyString(),
                                    anyString(),
                                    any(),
                                    anyString(),
                                    anyMap(),
                                    anyList(),
                                    anyString(),
                                    anyList()))
                            .thenReturn(new ToolInvocationGuard.GuardDecision.PauseForApproval(
                                    new ApprovalFrame("protected-tool", Map.of(), RiskLevel.MEDIUM)));
                    when(agentStore.load(anyString(), anyString()))
                            .thenReturn(Optional.of(new AgentCheckpointRecord()));

                    AgentExecutionContext ctx = new AgentExecutionContext(
                            "s1",
                            "u1",
                            "c1",
                            "coordinator",
                            "goal",
                            AutonomyLevel.SUPERVISED,
                            StructuredOutputMode.NATIVE,
                            5,
                            0,
                            "telegram",
                            "platform-1",
                            null,
                            null);
                    AgentResult result = runner.run(ctx, engine, List.of());

                    if (result instanceof AgentResult.Failed f) {
                        System.out.println("DEBUG Failure: " + f.reason() + ", category: " + f.category());
                    }
                    assertInstanceOf(AgentResult.AwaitingApproval.class, result);
                    assertEquals("protected-tool", ((AgentResult.AwaitingApproval) result).toolName());
                    return null;
                });
    }

    @Test
    void testCircuitBreakerTripsAfter3Failures() {
        ReasoningStep toolStep1 = ReasoningStep.pending("thought1", "broken-tool", Map.of("arg", 1), 100, 100);
        ReasoningStep toolStep2 = ReasoningStep.pending("thought2", "broken-tool", Map.of("arg", 2), 100, 100);
        ReasoningStep toolStep3 = ReasoningStep.pending("thought3", "broken-tool", Map.of("arg", 3), 100, 100);
        when(engine.nextStep(any(), anyString(), anyString(), anyList()))
                .thenReturn(toolStep1)
                .thenReturn(toolStep2)
                .thenReturn(toolStep3);
        when(toolRegistry.findTool("broken-tool")).thenReturn(Optional.of(mock(AgentTool.class)));
        when(guard.evaluate(anyString(), anyString(), any(), anyString(), anyMap(), anyList(), anyString(), anyList()))
                .thenReturn(new ToolInvocationGuard.GuardDecision.Allow());
        when(invoker.invoke(anyString(), anyMap(), anyString())).thenReturn(ToolResponse.failure(1, "error"));

        AgentExecutionContext ctx = new AgentExecutionContext(
                "s1",
                "u1",
                "c1",
                "coordinator",
                "goal",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                5,
                0,
                null,
                null,
                null,
                null);
        AgentResult result = runner.run(ctx, engine, List.of());

        assertInstanceOf(AgentResult.Finished.class, result);
        assertTrue(((AgentResult.Finished) result).answer().contains("❌"));
        verify(invoker, times(3)).invoke(anyString(), anyMap(), anyString());
    }

    @Test
    void testSchemaErrorSelfHealsOnce() {
        InvalidRequestException ex = new InvalidRequestException("tool call validation failed");
        ReasoningStep finishStep =
                ReasoningStep.pending("done", ReasoningStep.FINISH, Map.of("answer", "healed"), 100, 100);

        when(engine.nextStep(any(), anyString(), anyString(), anyList()))
                .thenThrow(ex)
                .thenReturn(finishStep);

        AgentExecutionContext ctx = new AgentExecutionContext(
                "s1",
                "u1",
                "c1",
                "coordinator",
                "goal",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                5,
                0,
                null,
                null,
                null,
                null);
        AgentResult result = runner.run(ctx, engine, List.of());

        assertInstanceOf(AgentResult.Finished.class, result);
        assertEquals("healed", ((AgentResult.Finished) result).answer());
    }

    @Test
    void testSchedulingToolFastPathExits() {
        ReasoningStep scheduleStep = ReasoningStep.pending(
                "planning", ToolCapability.SCHEDULE_TASK.toolName(), Map.of("task", "clean"), 100, 100);
        when(engine.nextStep(any(), anyString(), anyString(), anyList())).thenReturn(scheduleStep);
        when(toolRegistry.findTool(ToolCapability.SCHEDULE_TASK.toolName()))
                .thenReturn(Optional.of(mock(AgentTool.class)));
        when(guard.evaluate(anyString(), anyString(), any(), anyString(), anyMap(), anyList(), anyString(), anyList()))
                .thenReturn(new ToolInvocationGuard.GuardDecision.Allow());
        when(invoker.invoke(anyString(), anyMap(), anyString())).thenReturn(ToolResponse.success("Scheduled cleaning"));

        AgentExecutionContext ctx = new AgentExecutionContext(
                "s1",
                "u1",
                "c1",
                "coordinator",
                "goal",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                5,
                0,
                null,
                null,
                null,
                null);
        AgentResult result = runner.run(ctx, engine, List.of());

        assertInstanceOf(AgentResult.Finished.class, result);
        assertTrue(((AgentResult.Finished) result).answer().contains("Scheduled cleaning"));
    }

    @Test
    void testRateLimitReturnsRateLimitCategory() {
        HttpException ex = new HttpException(429, "Rate limit reached, try again in 20s");
        when(engine.nextStep(any(), anyString(), anyString(), anyList())).thenThrow(ex);

        AgentExecutionContext ctx = new AgentExecutionContext(
                "s1",
                "u1",
                "c1",
                "coordinator",
                "goal",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                5,
                0,
                null,
                null,
                null,
                null);
        AgentResult result = runner.run(ctx, engine, List.of());

        assertInstanceOf(AgentResult.AwaitingRetry.class, result);
        assertEquals(20000, ((AgentResult.AwaitingRetry) result).retryAfterMs());
    }

    @Test
    void testReactKeywordDoesNotPersistToHistory() {
        ReasoningStep reactStep = ReasoningStep.pending("I will observe the result", "OBSERVE", Map.of(), 100, 100);
        ReasoningStep finishStep =
                ReasoningStep.pending("done", ReasoningStep.FINISH, Map.of("answer", "fixed"), 100, 100);

        when(engine.nextStep(any(), any(), any(), anyList()))
                .thenReturn(reactStep)
                .thenReturn(finishStep);
        lenient().when(toolRegistry.findTool("OBSERVE")).thenReturn(Optional.empty());
        lenient()
                .when(toolRegistry.listTools())
                .thenReturn(List.of(ToolCapability.GITHUB.toolName(), ToolCapability.RECALL_MEMORY_SEARCH.toolName()));

        AgentExecutionContext ctx = new AgentExecutionContext(
                "s1",
                "u1",
                "c1",
                "coordinator",
                "goal",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                5,
                0,
                null,
                null,
                null,
                null);
        AgentResult result = runner.run(ctx, engine, List.of());

        assertInstanceOf(AgentResult.Finished.class, result);
        assertEquals("fixed", ((AgentResult.Finished) result).answer());
        assertFalse(
                result.steps().stream().anyMatch(s -> "OBSERVE".equals(s.action())),
                "OBSERVE ReAct keyword must NOT be persisted to reasoning history");
    }
}
