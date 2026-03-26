package com.arcclaw.core.agent.persona;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.ToolCapability;

class BrahmaAgentTest {

     private final BrahmaAgent agent = new BrahmaAgent();

    @Test
    void testAllowedToolsContainsSandboxExec() {
        Set<ToolCapability> caps = AgentRoleEnum.CODER.definition().capabilities();
        assertFalse(caps.isEmpty(),
            "Coder must have SANDBOX_EXEC capability — no longer empty since P67");
        assertTrue(caps.contains(ToolCapability.SANDBOX_EXEC),
            "Coder role must include SANDBOX_EXEC");
        assertEquals(1, caps.size(),
            "Coder must have exactly one capability: SANDBOX_EXEC only");
    }

    @Test
    void testRoleIsCoder() {
        assertEquals(AgentRoleEnum.CODER.toScopedName(), agent.role());
    }

    @Test
    void testAllowedToolsMatchRoleDefinition() {
        // allowedTools() delegates to AgentRoleEnum.CODER.definition().toolNames()
        // so both must agree — this is the contract test
        assertTrue(
            agent.allowedTools().containsAll(
                AgentRoleEnum.CODER.definition().capabilities()
                    .stream()
                    .map(ToolCapability::toolName)
                    .toList()),
            "BrahmaAgent.allowedTools() must include every tool in CODER capability set"
        );
    }

    @Test
    void testSystemPromptMentionsSandboxExec() {
        String prompt = agent.systemPrompt();
        assertTrue(prompt.contains("sandbox_exec") || prompt.contains("sandboxexec"),
            "BrahmaAgent system prompt must reference sandbox_exec tool");
        assertFalse(prompt.contains("PERMITTED TOOLS: None"),
            "BrahmaAgent prompt must not declare zero tools");
    }

    @Test
    void testSystemPromptDoesNotClaimGithubTool() {
        BrahmaAgent agent = new BrahmaAgent();
        assertFalse(agent.systemPrompt().contains("'github' tool"),
                "BrahmaAgent must not claim github tool it cannot access");
    }
}
