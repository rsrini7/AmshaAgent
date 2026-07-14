package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;

import com.amsha.persistence.api.ArchivalChunkDto;
import com.amsha.persistence.sqlite.jdbi.ArchivalMemoryJdbiDao;
import com.amsha.persistence.sqlite.testutil.FlywayEnabledTestProfile;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Regression test for archival search with a chunk_type filter.
 *
 * <p>The JDBI DAO uses the {@code <chunkTypes>} SQL-template placeholder, which requires
 * {@code @BindList} (not {@code @Bind}). Binding it with {@code @Bind} caused a runtime
 * "Error rendering SQL template" failure whenever the agent searched archival memory with a
 * type filter (e.g. "search my archival for CODE type chunks" / "search for patterns"). The
 * HTTP call still returned 200 because the agent loop swallowed the tool error, but the search
 * feature was broken. This test executes the query end-to-end so the binding can never regress.
 */
@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
class SqliteArchivalSearchByTypeTest {

    private static final String USER = "test-archival-user";

    @Inject
    Jdbi jdbi;

    @BeforeEach
    void setUp() {
        jdbi.useHandle(handle -> handle.createUpdate("DELETE FROM archival_chunks WHERE user_id = :u")
                .bind("u", USER)
                .execute());
    }

    private void insert(String id, String type, String content) {
        Instant now = Instant.now();
        ArchivalChunkDto dto = new ArchivalChunkDto(id, USER, content, type, "sess-1", "msg-1", null, 1.0, 0, now, now);
        jdbi.useExtension(ArchivalMemoryJdbiDao.class, dao -> dao.upsert(dto));
    }

    @Test
    void searchByTypesRendersTemplateAndFiltersByType() {
        insert("test-p1", "PATTERN", "Always use sealed classes for type hierarchies");
        insert("test-f1", "FACT", "Amsha Agent uses Quarkus with SQLite WAL mode");

        // Single-type filter — previously threw "Error rendering SQL template".
        List<ArchivalChunkDto> patternOnly = jdbi.withExtension(
                ArchivalMemoryJdbiDao.class, dao -> dao.searchByTypes(USER, "classes", 10, List.of("PATTERN")));
        assertEquals(1, patternOnly.size(), "PATTERN filter should return only the PATTERN chunk");
        assertEquals("PATTERN", patternOnly.get(0).chunkType());

        // Multi-value expansion of <chunkTypes>.
        List<ArchivalChunkDto> both = jdbi.withExtension(
                ArchivalMemoryJdbiDao.class,
                dao -> dao.searchByTypes(USER, "classes OR Quarkus", 10, List.of("PATTERN", "FACT")));
        assertTrue(both.size() >= 1, "Multi-type filter should render and execute without error");
    }
}
