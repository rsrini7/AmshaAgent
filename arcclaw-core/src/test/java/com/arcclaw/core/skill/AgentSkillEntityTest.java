package com.arcclaw.core.skill;

import org.junit.jupiter.api.Test;

import jakarta.persistence.Table;
import jakarta.persistence.Column;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AgentSkillEntityTest {

    @Test
    void testAllArgsConstructorAndAccessors() {
        Instant now = Instant.now();
        AgentSkillEntity entity = new AgentSkillEntity("id1", "my-skill", "yaml...", now, now);
        assertEquals("id1", entity.getId());
        assertEquals("my-skill", entity.getName());
        assertEquals("yaml...", entity.getDefinitionYaml());
        assertEquals(now, entity.getCreatedAt());
        assertEquals(now, entity.getUpdatedAt());

        entity.setDefinitionYaml("new-yaml...");
        assertEquals("new-yaml...", entity.getDefinitionYaml());

        Instant later = now.plusSeconds(10);
        entity.setUpdatedAt(later);
        assertEquals(later, entity.getUpdatedAt());
    }

    @Test
    void testTableName() {
        Table table = AgentSkillEntity.class.getAnnotation(Table.class);
        assertNotNull(table);
        assertEquals("agent_skill", table.name());
    }

    @Test
    void testNameColumnUnique() throws NoSuchFieldException {
        Field nameField = AgentSkillEntity.class.getDeclaredField("name");
        Column column = nameField.getAnnotation(Column.class);
        assertNotNull(column);
        assertTrue(column.unique());
        assertFalse(column.nullable());
    }
}
