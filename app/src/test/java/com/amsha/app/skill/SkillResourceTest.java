package com.amsha.app.skill;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.core.agent.role.RoleDefinition;
import com.amsha.core.audit.AuditService;
import com.amsha.core.skill.CompiledSkillEntry;
import com.amsha.core.skill.SkillDefinition;
import com.amsha.core.skill.SkillDefinitionParser;
import com.amsha.core.skill.SkillLoader;
import com.amsha.core.skill.SkillRegistry;
import com.amsha.persistence.api.SkillDto;
import com.amsha.persistence.api.SkillStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SkillResourceTest {

    @Mock
    SkillRegistry registry;

    @Mock
    SkillStorePort skillStore;

    @Mock
    SkillLoader skillLoader;

    @Mock
    SkillDefinitionParser parser;

    @Mock
    AuditService auditService;

    SkillResource resource;

    private static final String SKILL_NAME = "code-reviewer";

    @BeforeEach
    void setUp() {
        resource = new SkillResource();
        setField(resource, "registry", registry);
        setField(resource, "skillStore", skillStore);
        setField(resource, "skillLoader", skillLoader);
        setField(resource, "parser", parser);
        setField(resource, "auditService", auditService);
    }

    @Test
    void listSkillsReturns200() {
        CompiledSkillEntry entry = sampleSkillEntry();
        when(registry.getAll()).thenReturn(List.of(entry));

        List<SkillResource.SkillResponse> result = resource.listSkills();

        assertEquals(1, result.size());
        assertEquals(SKILL_NAME, result.get(0).name());
    }

    @Test
    void listSkillsReturnsEmptyList() {
        when(registry.getAll()).thenReturn(List.of());

        List<SkillResource.SkillResponse> result = resource.listSkills();

        assertTrue(result.isEmpty());
    }

    @Test
    void upsertSkillReturns201OnCreate() {
        String yaml = "---\nname: code-reviewer\n---\nReview code";
        SkillDefinition def = new SkillDefinition(
                "skill-1",
                SKILL_NAME,
                "Reviews code",
                List.of("review.*code"),
                List.of(),
                5,
                "You are a code reviewer.",
                null);
        when(parser.parse(yaml)).thenReturn(def);
        when(skillStore.findByName(SKILL_NAME)).thenReturn(Optional.empty());

        Response resp = resource.upsertSkill(yaml);

        assertEquals(201, resp.getStatus());
        verify(skillStore).save(any(SkillDto.class));
        verify(skillLoader).reloadAll(false);
    }

    @Test
    void upsertSkillReturns201OnUpdate() {
        String yaml = "---\nname: code-reviewer\n---\nUpdated prompt";
        SkillDefinition def = new SkillDefinition(
                "skill-1", SKILL_NAME, "Reviews code", List.of(), List.of(), 5, "Updated prompt.", null);
        SkillDto existing = new SkillDto("skill-1", SKILL_NAME, "old-yaml", Instant.now(), Instant.now());
        when(parser.parse(yaml)).thenReturn(def);
        when(skillStore.findByName(SKILL_NAME)).thenReturn(Optional.of(existing));

        Response resp = resource.upsertSkill(yaml);

        assertEquals(201, resp.getStatus());
        verify(skillStore).save(any(SkillDto.class));
    }

    @Test
    void upsertSkillReturns400ForInvalidYaml() {
        when(parser.parse("bad yaml")).thenThrow(new IllegalArgumentException("Invalid YAML"));

        Response resp = resource.upsertSkill("bad yaml");

        assertEquals(400, resp.getStatus());
    }

    @Test
    void deleteSkillReturns200WhenFound() {
        SkillDto existing = new SkillDto("skill-1", SKILL_NAME, "yaml", Instant.now(), Instant.now());
        when(skillStore.findByName(SKILL_NAME)).thenReturn(Optional.of(existing));
        when(skillStore.deleteById("skill-1")).thenReturn(true);

        Response resp = resource.deleteSkill(SKILL_NAME);

        assertEquals(200, resp.getStatus());
        verify(skillLoader).reloadAll(false);
    }

    @Test
    void deleteSkillReturns404WhenNotFound() {
        when(skillStore.findByName(SKILL_NAME)).thenReturn(Optional.empty());

        Response resp = resource.deleteSkill(SKILL_NAME);

        assertEquals(404, resp.getStatus());
    }

    @Test
    void deleteSkillReturns404WhenAlreadyDeleted() {
        SkillDto existing = new SkillDto("skill-1", SKILL_NAME, "yaml", Instant.now(), Instant.now());
        when(skillStore.findByName(SKILL_NAME)).thenReturn(Optional.of(existing));
        when(skillStore.deleteById("skill-1")).thenReturn(false);

        Response resp = resource.deleteSkill(SKILL_NAME);

        assertEquals(404, resp.getStatus());
    }

    @Test
    void refreshReturns200() {
        Response resp = resource.refresh();

        assertEquals(200, resp.getStatus());
        verify(skillLoader).reloadAll(true);
    }

    private CompiledSkillEntry sampleSkillEntry() {
        SkillDefinition def = new SkillDefinition(
                "skill-1",
                SKILL_NAME,
                "Reviews code",
                List.of("review.*code"),
                List.of(),
                5,
                "You are a code reviewer.",
                null);
        RoleDefinition roleDef =
                new RoleDefinition("skill:code-reviewer", "Code Reviewer", "Reviews code", 5, false, false, null, null);
        return new CompiledSkillEntry(def, List.of(), roleDef, "classpath");
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field: " + fieldName, e);
        }
    }
}
