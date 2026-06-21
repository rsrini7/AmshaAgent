package com.amsha.core.agent.loop;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.inject.Inject;

import java.util.Collections;
import java.util.Map;

import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.reasoning.StructuredOutputMode;
import com.amsha.core.governance.GovernanceStrategy;
import com.amsha.core.observability.AgentOtelContext;
import com.amsha.core.security.AutonomyLevel;

import org.junit.jupiter.api.Test;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class EngineStepHandlerOtelTest {

    @Inject
    EngineStepHandler handler;

    @InjectMock
    GovernanceStrategy governanceLayer;

    @InjectMock
    ReasoningEngine engine;

    @InjectMock
    AgentOtelContext mockOtelContext;

    @Test
    void testOtelCalledExactlyOncePerStep() {
        // Arrange
        ReasoningStep step = ReasoningStep.pending("thought", "FINISH", Map.of("answer", "done"), 10, 20);
        when(engine.nextStep(any(), anyString(), anyString(), any())).thenReturn(step);

        // Act
        AgentExecutionContext context = new AgentExecutionContext(
                "test-session",
                "user",
                "chat",
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
        handler.processEngineStep(context, engine, Collections.emptyList(), null, 0, 1);

        // Assert
        // recordLlmCall should be called EXACTLY once (from the observer loop).
        // If there were a direct call to mockOtelContext.recordLlmCall, it would be twice.
        verify(mockOtelContext, times(1)).recordLlmCall("test-session", 10, 20);
    }
}
