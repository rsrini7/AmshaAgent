package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.reasoning.StructuredOutputMode;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.observability.AgentObserver;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.tool.DynamicToolLookup;
import com.amsha.core.tools.AgentTool;
import com.amsha.core.tools.ToolRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ToolStepValidatorDelegationTest {
    @Mock
    ToolRegistry toolRegistry;

    @Mock
    Instance<AgentObserver> observers;

    @Mock
    Instance<DynamicToolLookup> dynamicToolLookup;

    private ToolStepValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ToolStepValidator(toolRegistry, observers, dynamicToolLookup);
        when(toolRegistry.findTool(ToolCapability.INGEST_DOCUMENT.toolName()))
                .thenReturn(Optional.of(mock(AgentTool.class)));
    }

    @Test
    void unauthorizedIngestRequestsFromEveryOtherPersonaDelegateToCoordinator() {
        for (AgentRoleEnum role : AgentRoleEnum.values()) {
            AgentExecutionContext context = context(role.id());
            ReasoningStep step = ReasoningStep.pending(
                    "analysis", ToolCapability.INGEST_DOCUMENT.toolName(), Map.of("text", "remember this"));

            StepDecision decision = validator.validate(context, List.of(), step, step.action(), 0);

            if (role == AgentRoleEnum.COORDINATOR
                    || role == AgentRoleEnum.SYNTHESIZER
                    || role == AgentRoleEnum.NURTURER) {
                assertNull(decision, () -> role.id() + " already owns ingest_document");
            } else {
                assertEquals(DecisionType.PROCEED, decision.type());
                assertEquals(
                        ToolCapability.DELEGATE_TO_AGENT.toolName(),
                        decision.step().action());
                assertEquals(
                        AgentRoleEnum.COORDINATOR_ID,
                        decision.step().actionInput().get("agent"));
            }
        }
    }

    private AgentExecutionContext context(String role) {
        return new AgentExecutionContext(
                "session-" + role,
                "user",
                "chat",
                role,
                "store this information",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                5,
                0,
                null,
                null,
                null,
                Map.of());
    }
}
