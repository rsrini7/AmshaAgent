package com.arcclaw.core.agent.role;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolCapabilityTest {

    @Test
    void testDelegateToAgentExistsAndCoordinatorOwnsIt() {
        // 1. Verify capability exists
        assertEquals("delegate_to_agent", ToolCapability.DELEGATE_TO_AGENT.toolName());
        String desc = ToolCapability.DELEGATE_TO_AGENT.description();
        assertTrue(desc.contains("Delegate a task"));
        assertTrue(desc.contains("shiva"), "Dynamic description should contain delegatable role aliases");
        assertTrue(desc.contains("brahma"));

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
