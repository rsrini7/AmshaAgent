package com.arcclaw.core.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.RoleRegistry;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class DynamicToolDiscoveryTest {

    @Inject
    RoleRegistry roleRegistry;

    @Inject
    ToolRegistry toolRegistry;

    @Test
    void testAnnotationDrivenCapabilityDiscovery() {
        // 1. Verify that the dynamic_test_tool is granted to researcher
        Set<String> researcherTools = roleRegistry.permittedTools(AgentRoleEnum.RESEARCHER);
        
        assertTrue(researcherTools.contains("dynamic_test_tool"), 
            "Researcher should have acquired dynamic_test_tool via annotation. Found tools: " + researcherTools);

        // 2. Verify coordinator also has it
        Set<String> coordinatorTools = roleRegistry.permittedTools(AgentRoleEnum.COORDINATOR);
        assertTrue(coordinatorTools.contains("dynamic_test_tool"),
            "Coordinator should have acquired dynamic_test_tool via annotation");

        // 3. Verify coder does NOT have it
        Set<String> coderTools = roleRegistry.permittedTools(AgentRoleEnum.CODER);
        assertTrue(!coderTools.contains("dynamic_test_tool"),
            "Coder should not have dynamic_test_tool");
    }
}
