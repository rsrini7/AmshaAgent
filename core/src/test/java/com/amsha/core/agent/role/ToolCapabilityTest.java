package com.amsha.core.agent.role;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolCapabilityTest {

    @Test
    void testDelegateToAgentExistsAndIsOwnedByMajorRoles() {
        // 1. Verify capability exists
        assertEquals("garuda", ToolCapability.DELEGATE_TO_AGENT.toolName());
        String desc = ToolCapability.DELEGATE_TO_AGENT.description();
        assertTrue(desc.contains("Delegate a task"), "Description should contain delegation verb");
        assertTrue(desc.contains("shiva"), "Dynamic description should contain delegatable role aliases");
        assertTrue(desc.contains("brahma"));
        assertTrue(desc.contains("ganesha"), "New personas should be in description");
        assertTrue(desc.contains("kartikeya"));
        assertTrue(desc.contains("lakshmi"));
        assertTrue(desc.contains("durga"));
        assertTrue(desc.contains("kali"));
        assertTrue(desc.contains("parvati"));
    }
}
