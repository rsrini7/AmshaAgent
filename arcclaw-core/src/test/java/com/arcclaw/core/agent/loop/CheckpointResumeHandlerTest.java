package com.arcclaw.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.IntentClassificationService;
import com.arcclaw.core.agent.reasoning.ChatHistoryAssembler;
import com.arcclaw.core.agent.reasoning.LangChainReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.reasoning.ReasoningStepParser;
import com.arcclaw.core.agent.reasoning.SystemPromptBuilder;
import com.arcclaw.core.agent.reasoning.port.LlmProviderPort;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.RoleRegistry;
import com.arcclaw.core.governance.GovernanceStrategy;
import com.arcclaw.core.governance.RateLimitTracker;
import com.arcclaw.core.observability.AgentOtelContext;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.core.tools.ToolResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CheckpointResumeHandlerTest {

    @Mock
    StatefulAgentStore agentStore;

    @Mock
    ToolRegistry toolRegistry;

    CheckpointResumeHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CheckpointResumeHandler(agentStore, toolRegistry, new ObjectMapper(), null, null, null, null);
    }

    private MessageContext createContext(String input, boolean resume) {
        ChannelUser user = new ChannelUser("user-1", "user-1", "user-1", ChannelType.TELEGRAM);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, input, "chat-1", Instant.now());
        MessageContext ctx = new MessageContext("chat-1", user, msg);
        ctx.setResumeSignal(resume);
        return ctx;
    }

    private AgentExecutionContext createCtx(String sessionId, String goal) {
        return new AgentExecutionContext(sessionId, "user", "chat", "coordinator", goal, AutonomyLevel.FULL, 5, 0, null, null, null, null);
    }

    @Test
    void testReturnsNullOnNonResumptionGoal() {
        MessageContext ctx = createContext("some normal goal", false);
        AgentResult result = handler.handle(createCtx("session", "some normal goal"), new ArrayList<>(), ctx);
        assertNull(result);
    }

    @Test
    void testDenialPath() {
        AgentCheckpoint checkpoint = new AgentCheckpoint();
        checkpoint.setSessionId("session");
        checkpoint.setAgentRole("coordinator");
        checkpoint.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        checkpoint.setPendingToolName("testTool");
        checkpoint.setPendingToolParametersJson("{}");

        when(agentStore.load("session", "coordinator")).thenReturn(Optional.of(checkpoint));

        MessageContext ctx = createContext(AgentSentinels.GOAL_DENY_APPROVED, true);
        AgentResult result = handler.handle(createCtx("session", AgentSentinels.GOAL_DENY_APPROVED), new ArrayList<>(), ctx);

        assertNotNull(result);
        assertEquals("❌ Tool execution was denied and the task has been cancelled.", ((AgentResult.Finished) result).answer());
        
        assertEquals(CheckpointStatus.FAILED, checkpoint.getStatus());
        assertNull(checkpoint.getPendingToolName());
        assertNull(checkpoint.getPendingToolParametersJson());
        verify(agentStore).save(checkpoint);
    }

    @Test
    void testApprovalPath() throws Exception {
        WorkspaceContext.runWithFullAutonomy("session", "user", "chat", "coordinator", () -> {
            AgentCheckpoint checkpoint = new AgentCheckpoint();
            checkpoint.setSessionId("session");
            checkpoint.setAgentRole("coordinator");
            checkpoint.setStatus(CheckpointStatus.AWAITING_APPROVAL);
            checkpoint.setPendingToolName("testTool");
            checkpoint.setPendingToolParametersJson("{\"param\":\"val\"}");

            when(agentStore.load("session", "coordinator")).thenReturn(Optional.of(checkpoint));
            when(toolRegistry.invokeTool(eq("testTool"), eq("{\"param\":\"val\"}"), eq("session")))
                .thenReturn(ToolResponse.success("tool done"));

            List<ReasoningStep> history = new ArrayList<>();
            MessageContext ctx = createContext(AgentSentinels.GOAL_RESUME_APPROVED, true);
            AgentResult result = handler.handle(createCtx("session", AgentSentinels.GOAL_RESUME_APPROVED), history, ctx);

            assertNull(result); // Null means continue loop
            assertEquals(1, history.size());
            assertEquals("tool done", history.get(0).observation());

            assertEquals(CheckpointStatus.IN_PROGRESS, checkpoint.getStatus());
            assertNull(checkpoint.getPendingToolName());
            assertNull(checkpoint.getPendingToolParametersJson());
            verify(agentStore).save(checkpoint);
        });
    }

    @Test
    void testToolExecutionFailurePath() throws Exception {
        WorkspaceContext.runWithFullAutonomy("session", "user", "chat", "coordinator", () -> {
            AgentCheckpoint checkpoint = new AgentCheckpoint();
            checkpoint.setSessionId("session");
            checkpoint.setAgentRole("coordinator");
            checkpoint.setStatus(CheckpointStatus.AWAITING_APPROVAL);
            checkpoint.setPendingToolName("testTool");
            checkpoint.setPendingToolParametersJson("{}");

            when(agentStore.load("session", "coordinator")).thenReturn(Optional.of(checkpoint));
            when(toolRegistry.invokeTool(eq("testTool"), eq("{}"), eq("session")))
                .thenThrow(new RuntimeException("boom"));

            List<ReasoningStep> history = new ArrayList<>();
            MessageContext ctx = createContext(AgentSentinels.GOAL_RESUME_APPROVED, true);
            AgentResult result = handler.handle(createCtx("session", AgentSentinels.GOAL_RESUME_APPROVED), history, ctx);

            assertNotNull(result);
            assertTrue(result instanceof AgentResult.Failed);
            assertTrue(((AgentResult.Failed) result).reason().contains("boom"));

            assertEquals(CheckpointStatus.FAILED, checkpoint.getStatus());
            verify(agentStore).save(checkpoint);
        });
    }

    // Moved from AgentLoopTest
    @Test
    void testResumedHistoryHasFlatToolArguments() throws Exception {
        LlmProviderPort model = mock(LlmProviderPort.class);
        ToolRegistry tr = mock(ToolRegistry.class);
        RoleRegistry roleRegistry = mock(RoleRegistry.class);
        SystemPromptBuilder promptBuilder = mock(SystemPromptBuilder.class);
        ChatHistoryAssembler historyAssembler = mock(ChatHistoryAssembler.class);
        ReasoningStepParser stepParser = mock(ReasoningStepParser.class);
        IntentClassificationService intentDetector = mock(IntentClassificationService.class);
        GovernanceStrategy governanceLayer = mock(GovernanceStrategy.class);
        RateLimitTracker rateLimitTracker = mock(RateLimitTracker.class);
        AgentOtelContext otelContext = mock(AgentOtelContext.class);
        MeterRegistry meterRegistry = mock(MeterRegistry.class);
        
        when(promptBuilder.build(any(), any(), any(), any())).thenReturn("MOCK SYSTEM PROMPT");
        when(intentDetector.isWriteIntent(anyString())).thenReturn(false);

        LangChainReasoningEngine engine = new LangChainReasoningEngine();
        
        Field field;
        field = engine.getClass().getDeclaredField("llmProviderPort");
        field.setAccessible(true);
        field.set(engine, model);

        field = engine.getClass().getDeclaredField("toolRegistry");
        field.setAccessible(true);
        field.set(engine, tr);

        field = engine.getClass().getDeclaredField("roleRegistry");
        field.setAccessible(true);
        field.set(engine, roleRegistry);

        field = engine.getClass().getDeclaredField("promptBuilder");
        field.setAccessible(true);
        field.set(engine, promptBuilder);

        field = engine.getClass().getDeclaredField("historyAssembler");
        field.setAccessible(true);
        field.set(engine, historyAssembler);

        field = engine.getClass().getDeclaredField("stepParser");
        field.setAccessible(true);
        field.set(engine, stepParser);

        field = engine.getClass().getDeclaredField("intentDetector");
        field.setAccessible(true);
        field.set(engine, intentDetector);

        field = engine.getClass().getDeclaredField("governanceLayer");
        field.setAccessible(true);
        field.set(engine, governanceLayer);

        field = engine.getClass().getDeclaredField("rateLimitTracker");
        field.setAccessible(true);
        field.set(engine, rateLimitTracker);

        field = engine.getClass().getDeclaredField("otelContext");
        field.setAccessible(true);
        field.set(engine, otelContext);

        field = engine.getClass().getDeclaredField("meterRegistry");
        field.setAccessible(true);
        field.set(engine, meterRegistry);

        WorkspaceContext.runWithFullAutonomy("test-session", "user", "chat", "coordinator", () -> {
            // Act
            Map<String, Object> params = Map.of("parameters", "{\"action\":\"repo\",\"command\":\"view\",\"target\":\"rsrini7/arcclaw\"}");
            ReasoningStep step = ReasoningStep.of("thought", "github", params, "obs", 100, 100);

            // Mock response to just return something
            when(model.nextStep(any(ChatRequest.class))).thenReturn(
                    // LangChain4j 0.26.x→1.x migration: renamed .from() to factory method
                    ChatResponse.builder().aiMessage(AiMessage.aiMessage("response")).build()
            );

            // Mock history assembler to return a message with tool call
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .name("github")
                    .arguments("{\"action\":\"repo\",\"command\":\"view\",\"target\":\"rsrini7/arcclaw\"}")
                    .build();
            when(historyAssembler.assemble(any(), any(), any())).thenReturn(List.of(
                    // LangChain4j 0.26.x→1.x migration: renamed .from() to factory method
                    AiMessage.aiMessage("thought", List.of(req))
            ));
            
            // Mock parser
            when(stepParser.parse(any(), any(), any())).thenReturn(
                ReasoningStep.pending("response", ReasoningStep.FINISH, Map.of(), 100, 100)
            );

            engine.nextStep(createCtx("test-session", "goal"), "coordinator", "goal", List.of(step));

            ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
            verify(model).nextStep(captor.capture());

            // Extract the messages and find the AiMessage that corresponds to the history
            List<ChatMessage> messages = captor.getValue().messages();
            boolean found = false;
            for (ChatMessage msg : messages) {
                if (msg instanceof AiMessage m && m.hasToolExecutionRequests()) {
                    ToolExecutionRequest r = m.toolExecutionRequests().get(0);
                    if ("github".equals(r.name())) {
                        found = true;
                        assertEquals("{\"action\":\"repo\",\"command\":\"view\",\"target\":\"rsrini7/arcclaw\"}",
                                r.arguments());
                    }
                }
            }
            assertTrue(found, "Should have found the reconstructed ToolExecutionRequest in chat messages");
        });
    }
}
