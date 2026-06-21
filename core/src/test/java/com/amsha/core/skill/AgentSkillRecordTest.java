package com.amsha.core.skill;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;

import com.amsha.core.domain.model.AgentSkillRecord;
import com.amsha.persistence.api.SkillDto;

import org.junit.jupiter.api.Test;

class AgentSkillRecordTest {

    @Test
    void testAccessors() {
        Instant now = Instant.now();
        SkillDto dto = new SkillDto("id1", "my-skill", "yaml...", now, now);
        AgentSkillRecord entity = AgentSkillRecord.fromDto(dto);
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
}
