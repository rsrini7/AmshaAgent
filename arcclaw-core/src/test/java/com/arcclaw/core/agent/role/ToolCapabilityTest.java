package com.arcclaw.core.agent.role;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ToolCapabilityTest {

    @Test
    void testDelegateToAgentExistsAndCoordinatorOwnsIt() {
        // 1. Verify capability exists
        assertEquals("delegate_to_agent", ToolCapability.DELEGATE_TO_AGENT.toolName());
        assertTrue(ToolCapability.DELEGATE_TO_AGENT.description().contains("Delegate a task"));

        // 2. Verify Coordinator has it
        RoleDefinition coordinatorDef = AgentRoleEnum.COORDINATOR.definition();
        assertTrue(coordinatorDef.capabilities().contains(ToolCapability.DELEGATE_TO_AGENT));

        // 3. Verify specifically that Coder and Debugger DO NOT have it
        RoleDefinition coderDef = AgentRoleEnum.CODER.definition();
        assertFalse(coderDef.capabilities().contains(ToolCapability.DELEGATE_TO_AGENT));

        RoleDefinition debuggerDef = AgentRoleEnum.DEBUGGER.definition();
        assertFalse(debuggerDef.capabilities().contains(ToolCapability.DELEGATE_TO_AGENT));
    }
}
