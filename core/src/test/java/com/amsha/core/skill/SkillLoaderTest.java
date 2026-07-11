package com.amsha.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.agent.role.CapabilityRegistry;
import com.amsha.core.agent.role.RoleDefinition;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.audit.TelemetryAuditService;
import com.amsha.persistence.api.SkillDto;
import com.amsha.persistence.api.SkillStorePort;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.quarkus.arc.ClientProxy;
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

    @Inject
    CapabilityRegistry capabilityRegistry;

    @InjectMock
    TelemetryAuditService telemetryAuditService;

    @InjectMock
    ClasspathSkillProvider classpathSkillProvider;

    @InjectMock
    SkillsJarSkillProvider skillsJarSkillProvider;

    @BeforeEach
    @Transactional
    void setup() {
        Mockito.when(classpathSkillProvider.sourceName()).thenReturn("classpath");
        Mockito.when(classpathSkillProvider.priority()).thenReturn(100);
        Mockito.when(classpathSkillProvider.load()).thenReturn(List.of());

        Mockito.when(skillsJarSkillProvider.sourceName()).thenReturn("skillsjars");
        Mockito.when(skillsJarSkillProvider.priority()).thenReturn(50);
        Mockito.when(skillsJarSkillProvider.load()).thenReturn(List.of());

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
    }

    @Test
    void testSkillsJarsSensitiveCapabilitiesSecurityCheck() throws Exception {
        SkillLoader realLoader = ClientProxy.unwrap(skillLoader);
        Method addSafeMethod =
                SkillLoader.class.getDeclaredMethod("addSafe", Map.class, SkillDefinition.class, String.class);
        addSafeMethod.setAccessible(true);

        SkillDefinition sensitiveDef = new SkillDefinition(
                null, "sensitive-skill", "Reviews code", List.of(), List.of("SANDBOX_EXEC"), 5, "Systems Prompt", null);
        Map<String, CompiledSkillEntry> map = new HashMap<>();

        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) addSafeMethod.invoke(realLoader, map, sensitiveDef, "skillsjars");

        assertTrue(warnings.isEmpty());
        CompiledSkillEntry entry = map.get("sensitive-skill");
        assertTrue(entry.roleDefinition().requiresApproval());

        roleRegistry.registerDynamic(entry.definition().name(), entry.roleDefinition());
        assertTrue(roleRegistry.requiresApprovalForTools("skill:sensitive-skill"));
        assertTrue(capabilityRegistry.hasCapability("skill:sensitive-skill", ToolCapability.SANDBOX_EXEC.toolName()));

        SkillDefinition nonSensitiveDef = new SkillDefinition(
                null,
                "nonsensitive-skill",
                "Reads memory",
                List.of(),
                List.of("RECALL_MEMORY_SEARCH"),
                5,
                "Systems Prompt",
                null);
        map.clear();
        addSafeMethod.invoke(realLoader, map, nonSensitiveDef, "skillsjars");
        CompiledSkillEntry entry2 = map.get("nonsensitive-skill");
        Assertions.assertFalse(entry2.roleDefinition().requiresApproval());

        map.clear();
        addSafeMethod.invoke(realLoader, map, sensitiveDef, "database");
        CompiledSkillEntry entry3 = map.get("sensitive-skill");
        Assertions.assertFalse(entry3.roleDefinition().requiresApproval());
    }

    @Test
    void testCapabilityRegistryDynamicGrantsRemovalOnReload() throws Exception {
        SkillLoader realLoader = ClientProxy.unwrap(skillLoader);
        Method addSafeMethod =
                SkillLoader.class.getDeclaredMethod("addSafe", Map.class, SkillDefinition.class, String.class);
        addSafeMethod.setAccessible(true);

        SkillDefinition skill = new SkillDefinition(
                null, "temp-reload-skill", "Desc", List.of(), List.of("GITHUB"), 5, "Systems Prompt", null);

        Map<String, CompiledSkillEntry> map = new HashMap<>();
        addSafeMethod.invoke(realLoader, map, skill, "skillsjars");

        String dynamicRoleName = "skill:temp-reload-skill";
        roleRegistry.registerDynamic(
                "temp-reload-skill", map.get("temp-reload-skill").roleDefinition());

        assertTrue(capabilityRegistry.hasCapability(dynamicRoleName, ToolCapability.GITHUB.toolName()));

        skillLoader.reloadAll(true);

        Assertions.assertFalse(capabilityRegistry.hasCapability(dynamicRoleName, ToolCapability.GITHUB.toolName()));
    }
}
