package com.arcclaw.core.agent;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.arcclaw.core.agent.reasoning.ChatHistoryAssembler;
import com.arcclaw.core.agent.reasoning.LangChainReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.reasoning.ReasoningStepParser;
import com.arcclaw.core.agent.reasoning.SystemPromptBuilder;
import com.arcclaw.core.agent.role.RoleRegistry;
import com.arcclaw.core.tools.ToolRegistry;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LangChainReasoningEnginePersonaTest {

    private LangChainReasoningEngine engine;
    private ChatLanguageModel mockChatLanguageModel;
    private SystemPromptBuilder mockPromptBuilder;
    private ChatHistoryAssembler mockHistoryAssembler;
    private ReasoningStepParser mockStepParser;

    @BeforeEach
    void setUp() throws Exception {
        engine = new LangChainReasoningEngine();

        // 1. Create mocks
        mockChatLanguageModel = mock(ChatLanguageModel.class);
        ToolRegistry mockToolRegistry = mock(ToolRegistry.class);
        RoleRegistry mockRoleRegistry = mock(RoleRegistry.class);
        mockPromptBuilder = mock(SystemPromptBuilder.class);
        mockHistoryAssembler = mock(ChatHistoryAssembler.class);
        mockStepParser = mock(ReasoningStepParser.class);

        // 2. Set up basic mock behavior to avoid NPEs
        when(mockRoleRegistry.permittedTools(any())).thenReturn(Set.of());
        when(mockToolRegistry.listTools()).thenReturn(Collections.emptyList());
        when(mockHistoryAssembler.assemble(any(), any(), any())).thenReturn(Collections.emptyList());

        ChatResponse mockResponse = mock(ChatResponse.class);
        when(mockResponse.aiMessage()).thenReturn(AiMessage.from("mock response"));
        when(mockChatLanguageModel.chat(any(ChatRequest.class))).thenReturn(mockResponse);

        ReasoningStep expectedStep = ReasoningStep.pending("mock thought", ReasoningStep.FINISH, Collections.emptyMap(), 0, 0);
        when(mockStepParser.parse(any(), any(), any())).thenReturn(expectedStep);

        // 3. Inject mocks via reflection
        injectField(engine, "chatLanguageModel", mockChatLanguageModel);
        injectField(engine, "toolRegistry", mockToolRegistry);
        injectField(engine, "roleRegistry", mockRoleRegistry);
        injectField(engine, "promptBuilder", mockPromptBuilder);
        injectField(engine, "historyAssembler", mockHistoryAssembler);
        injectField(engine, "stepParser", mockStepParser);
    }

    private void injectField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void testDebuggerRoleDoesNotSayCoordinator() {
        // Arrange
        String persona = "You are Shiva, the Debugger and Code Analyst.";
        when(mockPromptBuilder.build(anyString(), anyString())).thenReturn(persona);

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
        String persona = "You are Vishnu, the Coordinator. THE TRIMURTI";
        when(mockPromptBuilder.build(anyString(), anyString())).thenReturn(persona);

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
