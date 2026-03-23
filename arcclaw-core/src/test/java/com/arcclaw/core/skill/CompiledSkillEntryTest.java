package com.arcclaw.core.skill;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.arcclaw.core.agent.role.RoleDefinition;
import org.junit.jupiter.api.Test;

class CompiledSkillEntryTest {

    private final SkillDefinition mockDef = new SkillDefinition(
            "id-123", "reviewer", "desc", List.of(), List.of(), 5, "prompt");
    private final RoleDefinition mockRole = new RoleDefinition(
            "skill:reviewer", "reviewer", "desc", 5, false, Set.of());
    private final Pattern[] patterns = new Pattern[]{
            Pattern.compile("review.*code", Pattern.CASE_INSENSITIVE),
            Pattern.compile("check.*pr", Pattern.CASE_INSENSITIVE)
    };

    @Test
    void testMatchesTrueWhenInputMatchesPattern() {
        CompiledSkillEntry entry = new CompiledSkillEntry(mockDef, patterns, mockRole, "classpath");
        assertTrue(entry.matches("Please review this code for me"));
        assertTrue(entry.matches("Can you check my PR?"));
    }

    @Test
    void testMatchesFalseForNonMatchingInput() {
        CompiledSkillEntry entry = new CompiledSkillEntry(mockDef, patterns, mockRole, "classpath");
        assertFalse(entry.matches("What is the weather like?"));
        assertFalse(entry.matches("Review this document")); // regex requires "code"
    }

    @Test
    void testMatchesFalseForNullOrBlankInput() {
        CompiledSkillEntry entry = new CompiledSkillEntry(mockDef, patterns, mockRole, "classpath");
        assertFalse(entry.matches(null));
        assertFalse(entry.matches("   "));
        assertFalse(entry.matches(""));
    }

    @Test
    void testNullConstructorArgsThrow() {
        assertThrows(NullPointerException.class, () ->
            new CompiledSkillEntry(null, patterns, mockRole, "classpath")
        );
        assertThrows(NullPointerException.class, () ->
            new CompiledSkillEntry(mockDef, null, mockRole, "classpath")
        );
        assertThrows(NullPointerException.class, () ->
            new CompiledSkillEntry(mockDef, patterns, null, "classpath")
        );
        assertThrows(NullPointerException.class, () ->
            new CompiledSkillEntry(mockDef, patterns, mockRole, null)
        );
    }
}
