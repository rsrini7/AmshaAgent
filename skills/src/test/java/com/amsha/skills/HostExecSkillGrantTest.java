package com.amsha.skills;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.amsha.core.agent.role.CapabilityRegistry;
import com.amsha.core.skill.CompiledSkillEntry;
import com.amsha.core.skill.SkillRegistry;
import com.amsha.tool.api.ToolCapability;

import org.junit.jupiter.api.Test;

class HostExecSkillGrantTest {

    @Test
    void admittedSkillsJarReceivesConfiguredHostExecGrant() {
        CapabilityRegistry capabilities = mock(CapabilityRegistry.class);
        SkillRegistry skills = mock(SkillRegistry.class);
        CompiledSkillEntry admitted = mock(CompiledSkillEntry.class);
        when(admitted.source()).thenReturn("skillsjars");
        when(skills.findByName("jdb-debugger")).thenReturn(Optional.of(admitted));
        HostExecSkillGrant grant = grant(capabilities, skills, List.of("jdb-debugger"));

        grant.onStart(null);

        verify(capabilities).grant("skill:jdb-debugger", ToolCapability.HOST_EXEC.toolName());
    }

    @Test
    void absentOrQuarantinedSkillDoesNotReceiveConfiguredGrant() {
        CapabilityRegistry capabilities = mock(CapabilityRegistry.class);
        SkillRegistry skills = mock(SkillRegistry.class);
        when(skills.findByName("algorithmic-art")).thenReturn(Optional.empty());
        HostExecSkillGrant grant = grant(capabilities, skills, List.of("algorithmic-art"));

        grant.onStart(null);

        verify(capabilities, never()).grant("skill:algorithmic-art", ToolCapability.HOST_EXEC.toolName());
    }

    @Test
    void nonSkillsJarSkillDoesNotReceiveConfiguredGrant() {
        CapabilityRegistry capabilities = mock(CapabilityRegistry.class);
        SkillRegistry skills = mock(SkillRegistry.class);
        CompiledSkillEntry databaseSkill = mock(CompiledSkillEntry.class);
        when(databaseSkill.source()).thenReturn("database");
        when(skills.findByName("jdb-debugger")).thenReturn(Optional.of(databaseSkill));
        HostExecSkillGrant grant = grant(capabilities, skills, List.of("jdb-debugger"));

        grant.onStart(null);

        verify(capabilities, never()).grant("skill:jdb-debugger", ToolCapability.HOST_EXEC.toolName());
    }

    private static HostExecSkillGrant grant(
            CapabilityRegistry capabilities, SkillRegistry skills, List<String> configuredSkills) {
        HostExecSkillGrant grant = new HostExecSkillGrant();
        grant.capabilityRegistry = capabilities;
        grant.skillRegistry = skills;
        grant.hostExecSkills = configuredSkills;
        return grant;
    }
}
