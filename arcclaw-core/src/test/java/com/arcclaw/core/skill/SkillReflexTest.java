package com.arcclaw.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.agent.role.RoleDefinition;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.reflex.ReflexResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SkillReflexTest {

    private SkillRegistry mockRegistry;
    private SkillReflex reflex;

    @BeforeEach
    void setup() {
        mockRegistry = new SkillRegistry();
        reflex = new SkillReflex();
        reflex.registry = mockRegistry;
    }

    private MessageContext createContext(String input) {
        ChannelUser user = new ChannelUser("user1", "user1", "user1", ChannelType.TELEGRAM);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, input, "session1", Instant.now());
        return new MessageContext("chat1", user, msg);
    }

    @Test
    void testReturnsFalseForNullInput() {
        assertFalse(reflex.matches(null));
    }

    @Test
    void testReturnsFalseForNonCommand() {
        assertFalse(reflex.matches("Hello"));
        assertFalse(reflex.matches("/status"));
    }

    @Test
    void testMatchesCommandWithWhitespaceAndCase() {
        mockRegistry.reload(List.of());
        assertTrue(reflex.matches("/skills"));
        assertTrue(reflex.matches("  /skills  "));
        assertTrue(reflex.matches("/SKILLS"));
    }

    @Test
    void testEmptyRegistryOutput() {
        mockRegistry.reload(List.of());
        ReflexResult result = reflex.execute("session1", "user1", "chat1", "/skills", createContext("/skills"));
        assertEquals("No skills are currently loaded.", result.commandResponse().orElse(""));
    }

    @Test
    void testPopulatedRegistryOutput() {
        SkillDefinition def1 = new SkillDefinition("id1", "reviewer", "Reviews PRs", List.of("pattern"), List.of(), 5, "prompt");
        RoleDefinition role1 = new RoleDefinition(
            "skill:reviewer", "Reviewer", "Reviews PRs", 5, false, false, Set.of(), Set.of()
        );
        CompiledSkillEntry e1 = new CompiledSkillEntry(def1, new Pattern[]{Pattern.compile("pattern")}, role1, "db");

        mockRegistry.reload(List.of(e1));
        ReflexResult result = reflex.execute("session1", "user1", "chat1", "/skills", createContext("/skills"));
        String resp = result.commandResponse().orElse("");
        assertTrue(resp.contains("### Active Skills"));
        assertTrue(resp.contains("- **reviewer**: Reviews PRs (Routes: 1)"));
    }

    @Test
    void testBlankDescriptionFallback() {
        SkillDefinition def1 = new SkillDefinition("id1", "minimal", "", List.of(), List.of(), 5, "prompt");
        RoleDefinition role1 = new RoleDefinition(
            "skill:minimal", "Minimal", "", 5, false, false, Set.of(), Set.of()
        );
        CompiledSkillEntry e1 = new CompiledSkillEntry(def1, new Pattern[0], role1, "db");

        mockRegistry.reload(List.of(e1));
        ReflexResult result = reflex.execute("session1", "user1", "chat1", "/skills", createContext("/skills"));
        String resp = result.commandResponse().orElse("");
        assertTrue(resp.contains("- **minimal**: No description (Routes: 0)"));
    }
}
