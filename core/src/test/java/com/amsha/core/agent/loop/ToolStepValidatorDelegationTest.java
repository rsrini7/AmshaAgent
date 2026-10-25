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
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.observability.AgentObserver;
import com.amsha.core.tool.DynamicToolLookup;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.AgentTool;
import com.amsha.tool.api.ToolCapability;

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
        when(toolRegistry.findTool(ToolCapability.BROWSER_NAVIGATE.toolName()))
                .thenReturn(Optional.of(mock(AgentTool.class)));
    }

    @Test
    void unauthorizedBrowserRequestsFromEveryOtherPersonaDelegateToResearcher() {
        for (AgentRoleEnum role : AgentRoleEnum.values()) {
            AgentExecutionContext context = context(role.id());
            ReasoningStep step = ReasoningStep.pending(
                    "analysis", ToolCapability.BROWSER_NAVIGATE.toolName(), Map.of("url", "https://example.com"));

            StepDecision decision = validator.validate(context, List.of(), step, step.action(), 0);

            if (role == AgentRoleEnum.RESEARCHER) {
                assertNull(decision, () -> role.id() + " already owns browser_navigate");
            } else {
                assertEquals(DecisionType.PROCEED, decision.type());
                assertEquals(
                        ToolCapability.DELEGATE_TO_AGENT.toolName(),
                        decision.step().action());
                assertEquals(
                        AgentRoleEnum.RESEARCHER_ID,
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
