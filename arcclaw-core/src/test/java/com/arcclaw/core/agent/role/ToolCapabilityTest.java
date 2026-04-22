package com.arcclaw.core.agent.role;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolCapabilityTest {

    @Test
    void testDelegateToAgentExistsAndIsOwnedByMajorRoles() {
        // 1. Verify capability exists
        assertEquals("delegate_to_agent", ToolCapability.DELEGATE_TO_AGENT.toolName());
        String desc = ToolCapability.DELEGATE_TO_AGENT.description();
        assertTrue(desc.contains("Delegate a task"));
        assertTrue(desc.contains("shiva"), "Dynamic description should contain delegatable role aliases");
        assertTrue(desc.contains("brahma"));

        // 2. Verify Major Roles have it (B3 universal delegation)
        assertTrue(AgentRoleEnum.COORDINATOR.definition().capabilities().contains(ToolCapability.DELEGATE_TO_AGENT));
        assertTrue(AgentRoleEnum.CODER.definition().capabilities().contains(ToolCapability.DELEGATE_TO_AGENT));
        assertTrue(AgentRoleEnum.DEBUGGER.definition().capabilities().contains(ToolCapability.DELEGATE_TO_AGENT));
        assertTrue(AgentRoleEnum.RESEARCHER.definition().capabilities().contains(ToolCapability.DELEGATE_TO_AGENT));
    }
}
