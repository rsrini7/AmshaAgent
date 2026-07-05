package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.exception.HttpException;

import jakarta.enterprise.inject.Instance;

import java.util.ArrayList;
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
import com.amsha.core.agent.reasoning.StructuredOutputMode;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.agent.sse.AgentSseService;
import com.amsha.core.domain.model.AgentCheckpointRecord;
import com.amsha.core.governance.ApprovalFrame;
import com.amsha.core.governance.GovernanceStrategy;
import com.amsha.core.governance.RateLimitTracker;
import com.amsha.core.observability.AgentObserver;
import com.amsha.core.observability.AgentOtelContext;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.security.DestructiveVocabularyFilter;
import com.amsha.core.security.InputSanitizer;
import com.amsha.core.skill.SkillRegistry;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.tool.DynamicToolLookup;
import com.amsha.core.tools.AgentTool;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.tools.ToolResponse;
import com.amsha.core.util.TextPatternUtils;
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
class AgentStateMachineRunnerRegressionTest {

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
    private SimpleMeterRegistry meterRegistry;
    private BrowserPreflightHandler browserPreflightHandler;

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

        meterRegistry = new SimpleMeterRegistry();
        ToolStepHandler toolStepHandler = new ToolStepHandler(
                toolRegistry,
                observers,
                guard,
                invoker,
                writer,
                dynamicToolLookup,
                checkpointManager,
                parallelExecutor,
                meterRegistry,
                mock(ExecutionFailureRecoveryService.class),
                mock(PersonaEscalationService.class));

        StepDecisionRouter stepDecisionRouter =
                new StepDecisionRouter(finishActionHandler, toolStepHandler, writer, engineStepHandler);

        browserPreflightHandler = mock(BrowserPreflightHandler.class);
        lenient().when(browserPreflightHandler.run(any(), anyList())).thenReturn(Optional.empty());

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
                meterRegistry,
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

        lenient().when(piiTransformer.transform(any(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(piiScanReflex.sanitize(anyString())).thenAnswer(inv -> inv.getArgument(0));

        lenient().when(agentStore.saveWithSteps(any(), anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void testRateLimitRetryPreservesIterationCount() {
        HttpException rateLimitEx = new HttpException(429, "Rate limit hit");
        ReasoningStep finishStep =
                ReasoningStep.pending("done", ReasoningStep.FINISH, Map.of("answer", "success"), 100, 100);

        when(engine.nextStep(any(), anyString(), anyString(), anyList()))
                .thenThrow(rateLimitEx)
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
        assertEquals("success", ((AgentResult.Finished) result).answer());

        // Verify startIterationSpan was called twice with the SAME iteration number (1)
        verify(agentOtelContext, times(2)).startIterationSpan("s1", 1, "coordinator");

        // Verify overall logical iteration distribution is recorded as 1
        double recordedValue = meterRegistry
                .summary("amsha.agent.iterations.distribution", "role", "coordinator")
                .max();
        assertEquals(1.0, recordedValue);
    }

    @Test
    void testBrowserPreflightRunsExactlyOnce() {
        ReasoningStep toolStep = ReasoningStep.pending("thought", "valid-tool", Map.of(), 100, 100);
        ReasoningStep finishStep =
                ReasoningStep.pending("done", ReasoningStep.FINISH, Map.of("answer", "done"), 100, 100);

        when(engine.nextStep(any(), anyString(), anyString(), anyList()))
                .thenReturn(toolStep)
                .thenReturn(finishStep);
        when(toolRegistry.findTool("valid-tool")).thenReturn(Optional.of(mock(AgentTool.class)));
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
                5,
                0,
                null,
                null,
                null,
                null);
        AgentResult result = runner.run(ctx, engine, List.of());

        assertInstanceOf(AgentResult.Finished.class, result);
        verify(browserPreflightHandler, times(1)).run(any(), anyList());
    }

    @Test
    void testAwaitingApprovalTerminatesAndStampsState() throws Exception {
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
                                    new ApprovalFrame("protected-tool", Map.of(), ApprovalFrame.RiskLevel.MEDIUM)));
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

                    assertInstanceOf(AgentResult.AwaitingApproval.class, result);
                    assertEquals("protected-tool", ((AgentResult.AwaitingApproval) result).toolName());
                    return null;
                });
    }

    @Test
    void testRunFinishPassForceFinishRetry() {
        ReasoningStep finishStep =
                ReasoningStep.pending("done", ReasoningStep.FINISH, Map.of("answer", "terminal-ans"), 100, 100);
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

        AgentResult result = runner.runFinishPass(ctx, engine, new ArrayList<>());

        assertInstanceOf(AgentResult.Finished.class, result);
        assertEquals("terminal-ans", ((AgentResult.Finished) result).answer());
    }

    @Test
    void testRunFinishPassForcedResultWhenEngineKeepsCallingTools() {
        ReasoningStep toolStep = ReasoningStep.pending(
                        "read template",
                        ToolCapability.HOST_EXEC.toolName(),
                        Map.of("code", "cat viewer.html", "language", "bash"),
                        100,
                        100)
                .withObservation("<html>template content</html>", true);
        ReasoningStep repeatedToolStep = ReasoningStep.pending(
                "read again",
                ToolCapability.HOST_EXEC.toolName(),
                Map.of("code", "cat viewer.html", "language", "bash"),
                100,
                100);

        when(engine.nextStep(any(), anyString(), anyString(), anyList()))
                .thenReturn(repeatedToolStep)
                .thenReturn(repeatedToolStep);

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

        AgentResult result = runner.runFinishPass(ctx, engine, new ArrayList<>(List.of(toolStep)));

        assertInstanceOf(AgentResult.Finished.class, result);
        assertEquals(
                "The approved tool was executed successfully and the requested artifact was generated.",
                ((AgentResult.Finished) result).answer());
    }

    @Test
    void testRunFinishPassDoesNotEchoLargeHostExecOutput() {
        String largeHtml = ("<html>\n" + "  <body>\n" + "    <main>generated artifact</main>\n" + "  </body>\n"
                        + "</html>\n")
                .repeat(20);
        ReasoningStep toolStep = ReasoningStep.pending(
                        "render artifact",
                        ToolCapability.HOST_EXEC.toolName(),
                        Map.of("code", "cat viewer.html", "language", "bash"),
                        100,
                        100)
                .withObservation(largeHtml, true);

        when(engine.nextStep(any(), anyString(), anyString(), anyList()))
                .thenReturn(ReasoningStep.pending(
                        "still working",
                        ToolCapability.HOST_EXEC.toolName(),
                        Map.of("code", "cat viewer.html", "language", "bash"),
                        100,
                        100))
                .thenReturn(ReasoningStep.pending(
                        "still working",
                        ToolCapability.HOST_EXEC.toolName(),
                        Map.of("code", "cat viewer.html", "language", "bash"),
                        100,
                        100));

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

        AgentResult result = runner.runFinishPass(ctx, engine, new ArrayList<>(List.of(toolStep)));

        assertInstanceOf(AgentResult.Finished.class, result);
        String answer = ((AgentResult.Finished) result).answer();
        assertEquals("The approved tool was executed successfully and the requested artifact was generated.", answer);
        assertTrue(!answer.contains("<html>"));
    }
}
