package com.arcclaw.core.skill;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class AgentSkillRepositoryTest {

    @Inject
    AgentSkillRepository repository;

    @BeforeEach
    @Transactional
    void setup() {
        repository.deleteAll();
    }

    @Test
    void testSaveOrUpdateAndFindAll() {
        AgentSkillEntity entity = new AgentSkillEntity("u1", "test-skill", "yaml...", Instant.now(), Instant.now());
        repository.saveOrUpdate(entity);

        assertEquals(1, repository.findAllSkills().size());
        AgentSkillEntity loaded = repository.findAllSkills().get(0);
        assertEquals("test-skill", loaded.getName());
    }

    @Test
    void testFindByNameReturnsEmptyForUnknown() {
        Optional<AgentSkillEntity> found = repository.findByName("unknown-skill");
        assertTrue(found.isEmpty());
    }

    @Test
    void testSaveOrUpdateSameIdMerges() {
        AgentSkillEntity entity1 = new AgentSkillEntity("u1", "test-skill", "yaml-1", Instant.now(), Instant.now());
        repository.saveOrUpdate(entity1);

        AgentSkillEntity entity2 = new AgentSkillEntity("u1", "test-skill", "yaml-2", Instant.now(), Instant.now());
        repository.saveOrUpdate(entity2);

        assertEquals(1, repository.findAllSkills().size(), "Should merge into same row");
        AgentSkillEntity loaded = repository.findAllSkills().get(0);
        assertEquals("yaml-2", loaded.getDefinitionYaml());
    }
}
