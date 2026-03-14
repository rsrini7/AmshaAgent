package com.arcclaw.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.arcclaw.core.agent.role.RoleDefinition;
import com.arcclaw.core.agent.role.RoleRegistry;
import com.arcclaw.core.audit.TelemetryAuditService;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

@QuarkusTest
class SkillLoaderTest {

    @Inject
    SkillLoader skillLoader;

    @Inject
    SkillRegistry skillRegistry;

    @Inject
    RoleRegistry roleRegistry;

    @Inject
    AgentSkillRepository repository;

    @InjectMock
    TelemetryAuditService telemetryAuditService;

    @BeforeEach
    @Transactional
    void setup() {
        repository.deleteAll();
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
                  - MEMORYSEARCH
                max-iterations: 3
                ---
                Systems Prompt
                """;
        AgentSkillEntity entity = new AgentSkillEntity("id", "code-reviewer", yaml, Instant.now(), Instant.now());
        repository.saveOrUpdate(entity);

        skillLoader.reloadAll(false);

        assertEquals(1, skillRegistry.size());
        CompiledSkillEntry entry = skillRegistry.getAll().get(0);
        assertEquals("code-reviewer", entry.definition().name());
        assertEquals("database", entry.source());
        assertEquals(1, entry.triggerPatterns().length);

        Optional<RoleDefinition> dynRole = roleRegistry.getDynamicRoles().stream()
            .filter(r -> r.canonicalName().equals("skill:code-reviewer"))
            .findFirst();

        assertTrue(dynRole.isPresent());
        assertEquals(2, dynRole.get().capabilities().size());
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
        AgentSkillEntity entity = new AgentSkillEntity("id2", "bad-tool-skill", yaml, Instant.now(), Instant.now());
        repository.saveOrUpdate(entity);

        skillLoader.reloadAll(false);

        assertEquals(1, skillRegistry.size());
        CompiledSkillEntry entry = skillRegistry.getAll().get(0);
        
        // FAKE_TOOL_DOES_NOT_EXIST should be ignored, leaving only GITHUB (1 capability)
        assertEquals(1, entry.roleDefinition().capabilities().size());
        assertEquals("github", entry.roleDefinition().capabilities().iterator().next().toolName());
    }

    @Test
    @Transactional
    void testMalformedYamlSkillIsSkipped() {
        AgentSkillEntity entity = new AgentSkillEntity("id3", "broken-skill", "--- \n broken yaml", Instant.now(), Instant.now());
        repository.saveOrUpdate(entity);

        skillLoader.reloadAll(false);
        // Should not crash, just warn and leave registry empty
        assertEquals(0, skillRegistry.size());
    }
}
