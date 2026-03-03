package com.arcclaw.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.state.CompactionSummaryRepository;
import com.arcclaw.core.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

class AgentLoopTest {

    private ChatLanguageModel model;
    private ToolRegistry toolRegistry;
    private ObjectMapper objectMapper;
    private ChatMemoryStore chatMemoryStore;
    private CompactionSummaryRepository compactionSummaryRepository;
    private RoleRegistry roleRegistry;
    private PromptTemplateEngine promptTemplateEngine;
    private LangChainReasoningEngine engine;

    @BeforeEach
    void setUp() {
        model = mock(ChatLanguageModel.class);
        toolRegistry = mock(ToolRegistry.class);
        objectMapper = new ObjectMapper();
        chatMemoryStore = mock(ChatMemoryStore.class);
        compactionSummaryRepository = mock(CompactionSummaryRepository.class);
        roleRegistry = mock(RoleRegistry.class);
        promptTemplateEngine = mock(PromptTemplateEngine.class);
        when(compactionSummaryRepository.findLatestBySessionId(anyString()))
                .thenReturn(Optional.empty());
        when(promptTemplateEngine.buildSystemPrompt(any(), any(), any()))
                .thenReturn("MOCK SYSTEM PROMPT");

        engine = new LangChainReasoningEngine();
        engine.chatLanguageModel = model;
        engine.toolRegistry = toolRegistry;
        engine.objectMapper = objectMapper;
        engine.chatMemoryStore = chatMemoryStore;
        engine.compactionSummaryRepository = compactionSummaryRepository;
        engine.roleRegistry = roleRegistry;
        engine.promptTemplateEngine = promptTemplateEngine;
    }

    @Test
    void testResumedHistoryHasFlatToolArguments() {
        WorkspaceScopeHolder.run("test-session", () -> {
            // Create a ReasoningStep with toolName="github",
            // toolArguments="..." but stored via actionInput wrapped in "parameters" exactly like AgentLoop does
            Map<String, Object> params = Map.of("parameters", "{\"action\":\"repo\",\"command\":\"view\",\"target\":\"rsrini7/arcclaw\"}");
            ReasoningStep step = new ReasoningStep("thought", "github", params, "obs", 0, 0);

            // Mock response to just return something
            when(model.chat(any(ChatRequest.class))).thenReturn(
                    ChatResponse.builder().aiMessage(AiMessage.from("response")).build()
            );

            engine.nextStep("test-session", "coordinator", "goal", List.of(step));

            ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
            verify(model).chat(captor.capture());

            // Extract the messages and find the AiMessage that corresponds to the history
            List<ChatMessage> messages = captor.getValue().messages();
            boolean found = false;
            for (ChatMessage msg : messages) {
                if (msg instanceof AiMessage m && m.hasToolExecutionRequests()) {
                    ToolExecutionRequest req = m.toolExecutionRequests().get(0);
                    if ("github".equals(req.name())) {
                        found = true;
                        assertEquals("{\"action\":\"repo\",\"command\":\"view\",\"target\":\"rsrini7/arcclaw\"}",
                                req.arguments());
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
