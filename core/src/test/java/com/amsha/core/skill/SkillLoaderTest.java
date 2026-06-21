package com.amsha.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.Optional;

import com.amsha.core.agent.role.RoleDefinition;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.audit.TelemetryAuditService;
import com.amsha.persistence.api.SkillDto;
import com.amsha.persistence.api.SkillStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class SkillLoaderTest {

    @Inject
    SkillLoader skillLoader;

    @Inject
    SkillRegistry skillRegistry;

    @Inject
    RoleRegistry roleRegistry;

    @Inject
    SkillStorePort skillStore;

    @InjectMock
    TelemetryAuditService telemetryAuditService;

    @BeforeEach
    @Transactional
    void setup() {
        skillStore.deleteAll();
        skillLoader.reloadAll(false);
    }

    @Test
    void testEmptyDatabaseAndEmptyIndexLoadsZeroSkills() {
        // Assuming src/main/resources/skills/index.txt is empty
        skillLoader.reloadAll(false);
        assertEquals(0, skillRegistry.size());
    }

    @Test
    @Transactional
    void testLoadFromDatabasePopulatesRegistryAndRoleRegistry() {
        String yaml = """
                ---
                name: code-reviewer
                description: Reviews code
                trigger-patterns:
                  - "review.*code"
                tool-capabilities:
                  - GITHUB
                  - RECALL_MEMORY_SEARCH
                  - ARCHIVAL_MEMORY_SEARCH
                max-iterations: 3
                ---
                Systems Prompt
                """;
        skillStore.save(new SkillDto("id", "code-reviewer", yaml, Instant.now(), Instant.now()));

        skillLoader.reloadAll(false);

        assertEquals(1, skillRegistry.size());
        CompiledSkillEntry entry = skillRegistry.getAll().get(0);
        assertEquals("code-reviewer", entry.definition().name());
        assertEquals("database", entry.source());
        assertEquals(1, entry.triggerPatterns().size());

        Optional<RoleDefinition> dynRole = roleRegistry.getDynamicRoles().stream()
                .filter(r -> r.canonicalName().equals("skill:code-reviewer"))
                .findFirst();

        assertTrue(dynRole.isPresent());
        assertEquals(3, dynRole.get().capabilities().size());
    }

    @Test
    @Transactional
    void testUnknownToolCapabilityIsIgnored() {
        String yaml = """
                ---
                name: bad-tool-skill
                description: Uses an unknown tool
                tool-capabilities:
                  - GITHUB
                  - FAKE_TOOL_DOES_NOT_EXIST
                ---
                Systems Prompt
                """;
        skillStore.save(new SkillDto("id2", "bad-tool-skill", yaml, Instant.now(), Instant.now()));

        skillLoader.reloadAll(false);

        assertEquals(1, skillRegistry.size());
        CompiledSkillEntry entry = skillRegistry.getAll().get(0);

        // FAKE_TOOL_DOES_NOT_EXIST should be ignored, leaving only GITHUB (1 capability)
        assertEquals(1, entry.roleDefinition().capabilities().size());
        assertEquals(
                ToolCapability.GITHUB.toolName(),
                entry.roleDefinition().capabilities().iterator().next().toolName());
    }

    @Test
    @Transactional
    void testMalformedYamlSkillIsSkipped() {
        skillStore.save(new SkillDto("id3", "broken-skill", "--- \n broken yaml", Instant.now(), Instant.now()));

        skillLoader.reloadAll(false);
        // Should not crash, just warn and leave registry empty
        assertEquals(0, skillRegistry.size());
    }
}
