package com.arcclaw.core.agent;

import com.arcclaw.core.state.CompactionSummaryRepository;
import com.arcclaw.core.tools.ToolRegistry;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LangChainReasoningEnginePersonaTest {

    private LangChainReasoningEngine engine;
    private ChatLanguageModel mockChatLanguageModel;
    private AgentPersonaRegistry mockRegistry;
    private PromptTemplateEngine mockTemplateEngine;

    @BeforeEach
    void setUp() throws Exception {
        engine = new LangChainReasoningEngine();

        // 1. Create mocks
        mockChatLanguageModel = mock(ChatLanguageModel.class);
        ToolRegistry mockToolRegistry = mock(ToolRegistry.class);
        ObjectMapper mockMapper = mock(ObjectMapper.class);
        ChatMemoryStore mockMemoryStore = mock(ChatMemoryStore.class);
        CompactionSummaryRepository mockCompactionRepo = mock(CompactionSummaryRepository.class);
        RoleRegistry mockRoleRegistry = mock(RoleRegistry.class);
        mockTemplateEngine = mock(PromptTemplateEngine.class);
        mockRegistry = mock(AgentPersonaRegistry.class);

        // 2. Set up basic mock behavior to avoid NPEs
        when(mockRoleRegistry.permittedTools(any())).thenReturn(Collections.emptySet());
        when(mockToolRegistry.listTools()).thenReturn(Collections.emptyList());
        when(mockTemplateEngine.buildSystemPrompt(any(), any(), any())).thenReturn("PERMITTED TOOLS: none");
        when(mockCompactionRepo.findLatestBySessionId(any())).thenReturn(Optional.empty());

        ChatResponse mockResponse = mock(ChatResponse.class);
        when(mockResponse.aiMessage()).thenReturn(dev.langchain4j.data.message.AiMessage.from("mock response"));
        when(mockChatLanguageModel.chat(any(ChatRequest.class))).thenReturn(mockResponse);

        // 3. Inject mocks via reflection
        injectField(engine, "chatLanguageModel", mockChatLanguageModel);
        injectField(engine, "toolRegistry", mockToolRegistry);
        injectField(engine, "objectMapper", mockMapper);
        injectField(engine, "chatMemoryStore", mockMemoryStore);
        injectField(engine, "compactionSummaryRepository", mockCompactionRepo);
        injectField(engine, "roleRegistry", mockRoleRegistry);
        injectField(engine, "promptTemplateEngine", mockTemplateEngine);
        injectField(engine, "agentPersonaRegistry", mockRegistry);
    }

    private void injectField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void testDebuggerRoleDoesNotSayCoordinator() {
        // Arrange
        when(mockRegistry.getPersonaPrompt(AgentRoleEnum.DEBUGGER))
                .thenReturn("You are Shiva, the Debugger and Code Analyst.");

        // Act
        engine.nextStep("sess-1", "debugger", "fix this bug", Collections.emptyList());

        // Assert
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(mockChatLanguageModel).chat(captor.capture());

        ChatRequest request = captor.getValue();
        List<ChatMessage> messages = request.messages();
        SystemMessage sysMsg = (SystemMessage) messages.get(0);
        String promptText = sysMsg.text();

        assertTrue(promptText.contains("You are Shiva"), "Should contain Shiva persona");
        assertFalse(promptText.contains("Always identify as ArcClaw, specifically acting as the Coordinator"),
                "Debugger should NOT claim to be the Coordinator");
    }

    @Test
    void testCoordinatorRoleContainsTrimurtiContext() {
        // Arrange
        when(mockRegistry.getPersonaPrompt(AgentRoleEnum.COORDINATOR))
                .thenReturn("You are Vishnu, the Coordinator.");

        // Act
        engine.nextStep("sess-2", "coordinator", "plan tasks", Collections.emptyList());

        // Assert
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(mockChatLanguageModel).chat(captor.capture());

        ChatRequest request = captor.getValue();
        List<ChatMessage> messages = request.messages();
        SystemMessage sysMsg = (SystemMessage) messages.get(0);
        String promptText = sysMsg.text();

        assertTrue(promptText.contains("You are Vishnu"), "Should contain Vishnu persona");
        assertTrue(promptText.contains("THE TRIMURTI"), "Coordinator should receive architecture context");
    }
}
