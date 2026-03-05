package com.arcclaw.core.agent;

import com.arcclaw.core.governance.GovernanceLayer;
import com.arcclaw.core.observability.AgentObserver;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.state.CompactionSummaryRepository;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.tools.ToolRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

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
    void testSelfHealingOnHttp400() {
        WorkspaceScopeHolder.run("test-session", () -> {
            ReasoningEngine mockEngine = mock(ReasoningEngine.class);
            AgentLoop loop = new AgentLoop();
            Instance<AgentObserver> mockObservers = mock(Instance.class);
            when(mockObservers.iterator()).thenReturn(Collections.emptyIterator());
            doNothing().when(mockObservers).forEach(any());
            loop.observers = mockObservers;
            loop.chatMemoryStore = mock(ChatMemoryStore.class);
            loop.agentStore = mock(StatefulAgentStore.class);
            loop.toolRegistry = mock(ToolRegistry.class);
            loop.governanceLayer = mock(GovernanceLayer.class);

            // Throw InvalidRequestException on first call
            InvalidRequestException ex = new InvalidRequestException("missing properties: action");
            // Second call succeeds
            ReasoningStep finishStep = ReasoningStep.pending("done", ReasoningStep.FINISH, Map.of("answer", "success"), 0,
                    0);

            when(mockEngine.nextStep(any(), any(), any(), any()))
                    .thenThrow(ex)
                    .thenReturn(finishStep);

            AgentResult result = loop.run("test goal", mockEngine, 5);
            assertInstanceOf(AgentResult.Finished.class, result, "Loop should complete successfully after self-healing");
            assertEquals("success", ((AgentResult.Finished) result).answer());
        });
    }

    @Test
    void testSchemaErrorLimitExceeded() {
        WorkspaceScopeHolder.run("test-session", () -> {
            ReasoningEngine mockEngine = mock(ReasoningEngine.class);
            AgentLoop loop = new AgentLoop();
            Instance<AgentObserver> mockObservers = mock(Instance.class);
            when(mockObservers.iterator()).thenReturn(Collections.emptyIterator());
            doNothing().when(mockObservers).forEach(any());
            loop.observers = mockObservers;
            loop.chatMemoryStore = mock(ChatMemoryStore.class);
            loop.agentStore = mock(StatefulAgentStore.class);
            loop.toolRegistry = mock(ToolRegistry.class);
            loop.governanceLayer = mock(GovernanceLayer.class);

            InvalidRequestException ex = new InvalidRequestException("missing properties");
            // Always throw
            when(mockEngine.nextStep(any(), any(), any(), any())).thenThrow(ex);

            AgentResult result = loop.run("test goal", mockEngine, 5);
            assertInstanceOf(AgentResult.Failed.class, result, "Loop should fail since it exceeds schema retry limit");
            assertTrue(((AgentResult.Failed) result).reason().contains("SCHEMA_ERROR"));
        });
    }
}
