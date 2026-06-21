package com.amsha.core.agent.role;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class AgentRoleEnumSkillRoleTest {

    @Test
    void testIsSkillRole() {
        assertTrue(AgentRoleEnum.isSkillRole("skill:code-reviewer"));
        assertFalse(AgentRoleEnum.isSkillRole("coordinator"));
        assertFalse(AgentRoleEnum.isSkillRole(null));
        assertFalse(AgentRoleEnum.isSkillRole(""));
        assertFalse(AgentRoleEnum.isSkillRole("skill"));
    }

    @Test
    void testSkillName() {
        assertEquals("code-reviewer", AgentRoleEnum.skillName("skill:code-reviewer"));
        assertEquals("researcher", AgentRoleEnum.skillName("skill:researcher"));
    }

    @Test
    void testSkillName_Invalid() {
        assertThrows(IllegalArgumentException.class, () -> AgentRoleEnum.skillName("coordinator"));
        assertThrows(IllegalArgumentException.class, () -> AgentRoleEnum.skillName(null));
    }
}
