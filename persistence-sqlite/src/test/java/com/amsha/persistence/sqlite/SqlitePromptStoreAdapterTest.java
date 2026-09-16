package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.amsha.persistence.api.PromptDto;
import com.amsha.persistence.sqlite.testutil.FlywayEnabledTestProfile;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
class SqlitePromptStoreAdapterTest {

    @Inject
    SqlitePromptStoreAdapter store;

    @Test
    void testPromptStoreOperations() {
        String promptId = "prompt-" + UUID.randomUUID();
        String roleId = "role-test";
        String sectionKey = "system-instructions";

        PromptDto dto = new PromptDto(
                promptId,
                roleId,
                sectionKey,
                "Prompt template content",
                1,
                "ACTIVE",
                100,
                "{}",
                Instant.now(),
                Instant.now());

        store.save(dto);

        Optional<PromptDto> byIdOpt = store.findById(promptId);
        assertTrue(byIdOpt.isPresent());
        PromptDto byId = byIdOpt.get();
        assertEquals(promptId, byId.id());
        assertEquals(roleId, byId.roleId());
        assertEquals("Prompt template content", byId.content());

        Optional<PromptDto> activeOpt = store.findActive(roleId, sectionKey);
        assertTrue(activeOpt.isPresent());
        assertEquals(promptId, activeOpt.get().id());

        List<PromptDto> byRole = store.findByRole(roleId);
        assertFalse(byRole.isEmpty());

        List<PromptDto> all = store.findAll();
        assertTrue(all.size() >= 1);

        assertTrue(store.count() >= 1);

        assertTrue(store.deleteById(promptId));
        assertTrue(store.findById(promptId).isEmpty());
    }

    @Test
    void deleteByRoleAndKeyAndDeleteAll() {
        String roleId = "role-del-" + UUID.randomUUID();
        Instant now = Instant.now();
        store.save(new PromptDto(
                "prompt-a-" + roleId, roleId, "section-a", "content-a", 1, "ACTIVE", 100, "{}", now, now));
        store.save(new PromptDto(
                "prompt-b-" + roleId, roleId, "section-b", "content-b", 1, "ACTIVE", 100, "{}", now, now));

        assertEquals(2, store.findByRole(roleId).size());

        assertTrue(store.deleteByRoleAndKey(roleId, "section-a"));
        assertEquals(1, store.findByRole(roleId).size());
        assertFalse(store.deleteByRoleAndKey(roleId, "section-absent"));

        assertTrue(store.deleteAll() >= 1);
        assertEquals(0, store.count());
    }
}
