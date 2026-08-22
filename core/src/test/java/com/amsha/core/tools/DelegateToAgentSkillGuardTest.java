package com.amsha.core.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.amsha.security.api.AutonomyLevel;

import org.junit.jupiter.api.Test;

class DelegateToAgentSkillGuardTest {

    @Test
    void skillRoleCannotDelegateToAnotherPersona() {
        DelegateToAgentTool tool = new DelegateToAgentTool();
        ToolExecutionContext ctx = new ToolExecutionContext(
                "session-1",
                "user-1",
                "chat-1",
                "skill:algorithmic-art",
                AutonomyLevel.FULL,
                "trace-1",
                null,
                null,
                null,
                null,
                null,
                null);

        ToolResponse response = tool.execute(ctx, Map.of("agent", "brahma", "goal", "generate art"));

        assertTrue(response.success());
        assertTrue(response.output().contains("must execute the task directly"));
        assertTrue(response.output().contains("Do not delegate"));
    }
}
