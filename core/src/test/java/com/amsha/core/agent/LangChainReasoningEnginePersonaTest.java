package com.amsha.core.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.Collections;
import java.util.Set;

import com.amsha.core.agent.loop.AgentExecutionContext;
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
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.governance.GovernanceStrategy;
import com.amsha.core.governance.RateLimitTracker;
import com.amsha.core.observability.AgentOtelContext;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.util.TextPatternUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import io.micrometer.core.instrument.MeterRegistry;

class LangChainReasoningEnginePersonaTest {

    private LangChainReasoningEngine engine;
    private LlmProviderPort mockLlmProviderPort;
    private SystemPromptBuilder mockPromptBuilder;
    private ChatHistoryAssembler mockHistoryAssembler;
    private ReasoningStepParser mockStepParser;
    private IntentClassificationService mockIntentDetector;

    @BeforeEach
    void setUp() throws Exception {
        // 1. Create mocks
        mockLlmProviderPort = mock(LlmProviderPort.class);
        ToolRegistry mockToolRegistry = mock(ToolRegistry.class);
        RoleRegistry mockRoleRegistry = mock(RoleRegistry.class);
        mockPromptBuilder = mock(SystemPromptBuilder.class);
        mockHistoryAssembler = mock(ChatHistoryAssembler.class);
        mockStepParser = mock(ReasoningStepParser.class);
        mockIntentDetector = mock(IntentClassificationService.class);
        GovernanceStrategy mockGovernanceLayer = mock(GovernanceStrategy.class);
        RateLimitTracker mockRateLimitTracker = mock(RateLimitTracker.class);
        AgentOtelContext mockOtelContext = mock(AgentOtelContext.class);
        MeterRegistry mockMeterRegistry = mock(MeterRegistry.class);

        // 2. Set up basic mock behavior to avoid NPEs
        when(mockRoleRegistry.permittedTools(any())).thenReturn(Set.of());
        when(mockToolRegistry.listTools()).thenReturn(Collections.emptyList());
        when(mockHistoryAssembler.assemble(any(), any(), any())).thenReturn(Collections.emptyList());
        when(mockIntentDetector.isWriteIntent(anyString())).thenReturn(false);

        ChatResponse mockResponse = mock(ChatResponse.class);
        when(mockResponse.aiMessage()).thenReturn(AiMessage.aiMessage("mock response"));
        when(mockLlmProviderPort.nextStep(any(ChatRequest.class), any(String.class)))
                .thenReturn(mockResponse);

        ReasoningStep expectedStep =
                ReasoningStep.pending("mock thought", ReasoningStep.FINISH, Collections.emptyMap(), 0, 0);
        when(mockStepParser.parse(any(), any(), any())).thenReturn(expectedStep);

        // 3. Setup Collaborators
        ReasoningMessageAssembler messageAssembler =
                new ReasoningMessageAssembler(mockHistoryAssembler, mockToolRegistry);
        RecoveryProcessor recoveryProcessor = new RecoveryProcessor(new ObjectMapper(), new TextPatternUtils());
        ReasoningValidator reasoningValidator = new ReasoningValidator(mock(CompactionSummaryCache.class));
        StructuredOutputProcessor structuredOutputProcessor = new StructuredOutputProcessor(
                mockPromptBuilder, mockLlmProviderPort, mockGovernanceLayer, mockOtelContext);

        // 4. Instantiate engine using full constructor
        engine = new LangChainReasoningEngine(
                mockLlmProviderPort,
                mockRoleRegistry,
                mockPromptBuilder,
                mockRateLimitTracker,
                mockIntentDetector,
                mockMeterRegistry,
                new TextPatternUtils(),
                mockStepParser,
                messageAssembler,
                recoveryProcessor,
                structuredOutputProcessor,
                reasoningValidator,
                null);

        // 5. Inject otelContext via reflection (field injection, not constructor)
        try {
            var otelField = LangChainReasoningEngine.class.getDeclaredField("otelContext");
            otelField.setAccessible(true);
            otelField.set(engine, mockOtelContext);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject otelContext", e);
        }
    }

    @Test
    void testNextStepUsesSystemPromptFromBuilder() {
        // Arrange
        String expectedPrompt = "Custom test persona";
        // Use any() for all String arguments because sessionId might be null in mock context
        when(mockPromptBuilder.build(any(), any(), any(), any(), ArgumentMatchers.anyBoolean()))
                .thenReturn(expectedPrompt);

        AgentExecutionContext context = mock(AgentExecutionContext.class);
        when(context.sessionId()).thenReturn("s1");

        // Act
        engine.nextStep(context, "coordinator", "test goal", Collections.emptyList());

        // Assert
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        Mockito.verify(mockLlmProviderPort).nextStep(captor.capture(), any(String.class));

        String actualPrompt = findSystemMessageText(captor.getValue());
        assertTrue(expectedPrompt.equals(actualPrompt), "System prompt should match the one from builder");
    }

    @Test
    void testCoordinatorPersonaPromptContainsVishnuAndArchitecture() {
        // Arrange
        String persona = "You are Vishnu, the Coordinator. You are the main orchestrator...";
        String architecture = "YOUR INTERNAL ARCHITECTURE (THE TRIMURTI...):";
        String prompt = persona + "\n\n" + architecture;

        when(mockPromptBuilder.build(any(), any(), any(), any(), ArgumentMatchers.anyBoolean()))
                .thenReturn(prompt);

        AgentExecutionContext context = mock(AgentExecutionContext.class);
        when(context.sessionId()).thenReturn("s1");

        // Act
        engine.nextStep(context, "coordinator", "Hello", Collections.emptyList());

        // Assert
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        Mockito.verify(mockLlmProviderPort).nextStep(captor.capture(), any(String.class));

        String actualPrompt = findSystemMessageText(captor.getValue());
        assertTrue(actualPrompt != null && !actualPrompt.isBlank(), "Should have a non-blank system message");
        assertTrue(actualPrompt.contains("You are Vishnu"), "Should contain Vishnu persona");
        assertTrue(actualPrompt.contains("THE TRIMURTI"), "Coordinator should receive architecture context");
    }

    @Test
    void testSpecialistPersonaDoesNotContainArchitectureContextByDefaultUnlessCoordinator() {
        // Arrange
        String persona = "You are Shiva, the Debugger and Code Analyst.";
        when(mockPromptBuilder.build(any(), any(), any(), any(), ArgumentMatchers.anyBoolean()))
                .thenReturn(persona);

        AgentExecutionContext context = mock(AgentExecutionContext.class);
        when(context.sessionId()).thenReturn("s1");

        // Act
        engine.nextStep(context, "debugger", "Fix this", Collections.emptyList());

        // Assert
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        Mockito.verify(mockLlmProviderPort).nextStep(captor.capture(), any(String.class));

        String actualPrompt = findSystemMessageText(captor.getValue());
        assertTrue(actualPrompt != null && !actualPrompt.isBlank(), "Should have a non-blank system message");
        assertTrue(actualPrompt.contains("You are Shiva"), "Should contain Shiva persona");
        assertFalse(
                actualPrompt.contains("THE TRIMURTI"),
                "Specialist should NOT receive architecture context unless explicitly added");
    }

    private String findSystemMessageText(ChatRequest request) {
        if (request == null || request.messages() == null) return null;
        for (ChatMessage m : request.messages()) {
            if (m instanceof SystemMessage sm) {
                return sm.text();
            }
        }
        return null;
    }
}
