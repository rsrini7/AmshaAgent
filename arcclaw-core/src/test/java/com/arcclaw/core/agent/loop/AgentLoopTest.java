package com.arcclaw.core.agent.loop;

import com.arcclaw.core.agent.role.RoleRegistry;

import com.arcclaw.core.agent.reasoning.ReasoningStep;

import com.arcclaw.core.agent.reasoning.LangChainReasoningEngine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.arcclaw.core.agent.reasoning.ChatHistoryAssembler;
import com.arcclaw.core.agent.reasoning.ReasoningStepParser;
import com.arcclaw.core.agent.reasoning.SystemPromptBuilder;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.tools.ToolRegistry;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

class AgentLoopTest {

    private ChatLanguageModel model;
    private ToolRegistry toolRegistry;
    private RoleRegistry roleRegistry;
    private SystemPromptBuilder promptBuilder;
    private ChatHistoryAssembler historyAssembler;
    private ReasoningStepParser stepParser;
    private LangChainReasoningEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        model = mock(ChatLanguageModel.class);
        toolRegistry = mock(ToolRegistry.class);
        roleRegistry = mock(RoleRegistry.class);
        promptBuilder = mock(SystemPromptBuilder.class);
        historyAssembler = mock(ChatHistoryAssembler.class);
        stepParser = mock(ReasoningStepParser.class);
        
        when(promptBuilder.build(any(), any())).thenReturn("MOCK SYSTEM PROMPT");

        engine = new LangChainReasoningEngine();
        
        // Use reflection to set private fields
        setField(engine, "chatLanguageModel", model);
        setField(engine, "toolRegistry", toolRegistry);
        setField(engine, "roleRegistry", roleRegistry);
        setField(engine, "promptBuilder", promptBuilder);
        setField(engine, "historyAssembler", historyAssembler);
        setField(engine, "stepParser", stepParser);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void testResumedHistoryHasFlatToolArguments() {
        WorkspaceScopeHolder.run("test-session", () -> {
            // Act
            Map<String, Object> params = Map.of("parameters", "{\"action\":\"repo\",\"command\":\"view\",\"target\":\"rsrini7/arcclaw\"}");
            ReasoningStep step = new ReasoningStep("thought", "github", params, "obs", 0, 0);

            // Mock response to just return something
            when(model.chat(any(ChatRequest.class))).thenReturn(
                    ChatResponse.builder().aiMessage(AiMessage.from("response")).build()
            );

            // Mock history assembler to return a message with tool call
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .name("github")
                    .arguments("{\"action\":\"repo\",\"command\":\"view\",\"target\":\"rsrini7/arcclaw\"}")
                    .build();
            when(historyAssembler.assemble(any(), any(), any())).thenReturn(List.of(
                    AiMessage.from("thought", List.of(req))
            ));
            
            // Mock parser
            when(stepParser.parse(any(), any(), any())).thenReturn(
                ReasoningStep.pending("response", ReasoningStep.FINISH, Map.of(), 0, 0)
            );

            engine.nextStep("test-session", "coordinator", "goal", List.of(step));

            ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
            verify(model).chat(captor.capture());

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

    @Test
    void testAgentLoopDelegatesToStateMachineRunner() {
        // Reflection check to ensure methods moved to the runner are indeed gone from AgentLoop
        Class<AgentLoop> clazz = AgentLoop.class;
        String[] deletedMethods = {"runLoop", "processEngineStep", "validateTool", "invokeToolAndObserve", "handleFinishAction", "saveGoalToChatMemory"};
        
        for (String methodName : deletedMethods) {
            boolean exists = java.util.Arrays.stream(clazz.getDeclaredMethods())
                    .anyMatch(m -> m.getName().equals(methodName));
            assertFalse(exists, "Method " + methodName + " should have been deleted from AgentLoop");
        }
        
        // Also check if the injection is there
        boolean hasRunner = java.util.Arrays.stream(clazz.getDeclaredFields())
                .anyMatch(f -> f.getName().equals("stateMachineRunner"));
        assertTrue(hasRunner, "AgentLoop should have stateMachineRunner injected");
    }
}
