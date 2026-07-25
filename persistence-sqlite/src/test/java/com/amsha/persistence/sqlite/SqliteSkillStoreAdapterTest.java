package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.amsha.persistence.api.SkillDto;
import com.amsha.persistence.sqlite.testutil.FlywayEnabledTestProfile;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
class SqliteSkillStoreAdapterTest {

    @Inject
    SqliteSkillStoreAdapter store;

    @Test
    void testSkillStoreOperations() {
        String skillId = "skill-" + UUID.randomUUID();
        String name = "custom-skill-" + UUID.randomUUID();

        SkillDto dto = new SkillDto(skillId, name, "Skill definition yaml content", Instant.now(), Instant.now());

        store.save(dto);

        Optional<SkillDto> byNameOpt = store.findByName(name);
        assertTrue(byNameOpt.isPresent());
        SkillDto byName = byNameOpt.get();
        assertEquals(skillId, byName.id());
        assertEquals(name, byName.name());

        List<SkillDto> all = store.findAll();
        assertFalse(all.isEmpty());

        assertTrue(store.deleteById(skillId));
        assertTrue(store.findByName(name).isEmpty());
    }
}
