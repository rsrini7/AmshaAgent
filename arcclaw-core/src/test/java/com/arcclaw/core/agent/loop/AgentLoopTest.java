package com.arcclaw.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.arcclaw.core.agent.reasoning.ChatHistoryAssembler;
import com.arcclaw.core.agent.reasoning.LangChainReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStepParser;
import com.arcclaw.core.agent.reasoning.SystemPromptBuilder;
import com.arcclaw.core.agent.role.RoleRegistry;
import com.arcclaw.core.tools.ToolRegistry;

import dev.langchain4j.model.chat.ChatModel;

class AgentLoopTest {

    private ChatModel model;
    private ToolRegistry toolRegistry;
    private RoleRegistry roleRegistry;
    private SystemPromptBuilder promptBuilder;
    private ChatHistoryAssembler historyAssembler;
    private ReasoningStepParser stepParser;
    private LangChainReasoningEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        model = mock(ChatModel.class);
        toolRegistry = mock(ToolRegistry.class);
        roleRegistry = mock(RoleRegistry.class);
        promptBuilder = mock(SystemPromptBuilder.class);
        historyAssembler = mock(ChatHistoryAssembler.class);
        stepParser = mock(ReasoningStepParser.class);
        
        when(promptBuilder.build(any(), any())).thenReturn("MOCK SYSTEM PROMPT");

        engine = new LangChainReasoningEngine();
        
        // Use reflection to set private fields
        setField(engine, "chatModel", model);
        setField(engine, "toolRegistry", toolRegistry);
        setField(engine, "roleRegistry", roleRegistry);
        setField(engine, "promptBuilder", promptBuilder);
        setField(engine, "historyAssembler", historyAssembler);
        setField(engine, "stepParser", stepParser);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }



    @Test
    void testAgentLoopDelegatesToStateMachineRunner() {
        // Reflection check to ensure methods moved to the runner are indeed gone from AgentLoop
        Class<AgentLoop> clazz = AgentLoop.class;
        String[] deletedMethods = {"runLoop", "processEngineStep", "validateTool", "invokeToolAndObserve", "handleFinishAction", "saveGoalToChatMemory"};
        
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
}
