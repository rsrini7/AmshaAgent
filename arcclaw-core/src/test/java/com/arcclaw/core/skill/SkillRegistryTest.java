package com.arcclaw.core.skill;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class SkillRegistryTest {

    private final SkillRegistry registry = new SkillRegistry();

    private CompiledSkillEntry createMockEntry(String name, String regex) {
        SkillDefinition def = new SkillDefinition("id", name, "desc", List.of(), List.of(), 5, "prompt");
        Pattern[] patterns = new Pattern[]{Pattern.compile(regex, Pattern.CASE_INSENSITIVE)};
        com.arcclaw.core.agent.role.RoleDefinition role = new com.arcclaw.core.agent.role.RoleDefinition(
            "skill:" + name, name, "desc", 5, false, java.util.Set.of()
        );
        return new CompiledSkillEntry(def, patterns, role, "classpath");
    }

    @Test
    void testInitialStateIsEmpty() {
        assertEquals(0, registry.size());
        assertTrue(registry.getAll().isEmpty());
    }

    @Test
    void testReloadReplacesContent() {
        CompiledSkillEntry e1 = createMockEntry("skill1", "match1");
        registry.reload(List.of(e1));
        assertEquals(1, registry.size());

        CompiledSkillEntry e2 = createMockEntry("skill2", "match2");
        registry.reload(List.of(e2)); // Should completely replace e1
        assertEquals(1, registry.size());
        assertEquals("skill2", registry.getAll().get(0).definition().name());
    }

    @Test
    void testFindMatchReturnsFirstTrueMatch() {
        CompiledSkillEntry e1 = createMockEntry("code-reviewer", "review.*code");
        CompiledSkillEntry e2 = createMockEntry("bug-hunter", "find.*bugs");
        registry.reload(List.of(e1, e2));

        Optional<CompiledSkillEntry> match = registry.findMatch("Can you find some bugs?");
        assertTrue(match.isPresent());
        assertEquals("bug-hunter", match.get().definition().name());
    }

    @Test
    void testFindMatchReturnsEmptyWhenNoMatches() {
        CompiledSkillEntry e1 = createMockEntry("code-reviewer", "review.*code");
        registry.reload(List.of(e1));

        Optional<CompiledSkillEntry> match = registry.findMatch("What time is it?");
        assertTrue(match.isEmpty());
    }

    @Test
    void testFindMatchHandlesNullAndBlankSafely() {
        CompiledSkillEntry e1 = createMockEntry("code-reviewer", ".*");
        registry.reload(List.of(e1));

        assertTrue(registry.findMatch(null).isEmpty());
        assertTrue(registry.findMatch("").isEmpty());
        assertTrue(registry.findMatch("   ").isEmpty());
    }
    
    @Test
    void testReloadHandlesDuplicateNamesByOverwriting() {
        CompiledSkillEntry e1 = createMockEntry("duplicate", "match1");
        CompiledSkillEntry e2 = createMockEntry("duplicate", "match2");
        registry.reload(List.of(e1, e2));
        
        // Internal reload logic uses a map, so duplicates in the input list
        // result in the last one winning.
        assertEquals(1, registry.size());
    }
}
