package com.amsha.persistence.postgres;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.SkillDto;
import com.amsha.persistence.api.SkillStorePort;
import com.amsha.persistence.postgres.jdbi.SkillJdbiDao;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.extension.ExtensionCallback;
import org.jdbi.v3.core.extension.ExtensionConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link PostgresSkillStoreAdapter}.
 *
 * <p>No test class mentioned this adapter at all, so all 11 of its lines were uncovered. It is a pure
 * delegate onto {@link SkillJdbiDao}, and the two lines most worth having are the ones that look like
 * trivial pass-throughs but are not: {@code findAll} and {@code deleteAll} are handed the DAO's own method
 * references, so a future signature change would break the wiring rather than silently return an empty
 * list. Both are asserted through their real return values.
 */
class PostgresSkillStoreAdapterTest {

    private Jdbi jdbi;
    private SkillJdbiDao dao;
    private PostgresSkillStoreAdapter adapter;

    @BeforeEach
    void setUp() {
        jdbi = mock(Jdbi.class);
        dao = mock(SkillJdbiDao.class);
        adapter = new PostgresSkillStoreAdapter(jdbi);
    }

    private void givenConsumer() {
        doAnswer(invocation -> {
                    ExtensionConsumer consumer = invocation.getArgument(1);
                    consumer.useExtension(dao);
                    return null;
                })
                .when(jdbi)
                .useExtension(eq(SkillJdbiDao.class), any());
    }

    private void givenExtension() {
        when(jdbi.withExtension(eq(SkillJdbiDao.class), any())).thenAnswer(invocation -> {
            ExtensionCallback callback = invocation.getArgument(1);
            return callback.withExtension(dao);
        });
    }

    private static SkillDto skill(String id, String name) {
        Instant now = Instant.now();
        return new SkillDto(id, name, "name: " + name, now, now);
    }

    @Test
    void implementsSkillStorePort() {
        assertTrue(SkillStorePort.class.isAssignableFrom(PostgresSkillStoreAdapter.class));
    }

    @Test
    @DisplayName("the protected no-arg constructor builds an unconfigured instance")
    void theProtectedNoArgConstructorBuildsAnUnconfiguredInstance() {
        assertDoesNotThrow(() -> {
            var ctor = PostgresSkillStoreAdapter.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            ctor.newInstance();
        });
    }

    @Test
    @DisplayName("save forwards the DTO to the DAO")
    void saveForwardsTheDto() {
        givenConsumer();
        SkillDto dto = skill("skill-1", "summarize");

        adapter.save(dto);

        verify(dao).save(dto);
    }

    @Test
    @DisplayName("findAll and deleteAll are passed the DAO's own method references")
    void findAllAndDeleteAllDelegateToTheDao() {
        givenExtension();
        when(dao.findAll()).thenReturn(List.of(skill("skill-1", "summarize")));
        when(dao.deleteAll()).thenReturn(2L);

        assertEquals(1, adapter.findAll().size());
        assertEquals("skill-1", adapter.findAll().getFirst().id());
        assertEquals(2L, adapter.deleteAll());
    }

    @Test
    @DisplayName("findByName and deleteById carry their argument through")
    void findByNameAndDeleteByIdCarryTheirArgument() {
        givenExtension();
        when(dao.findByName("summarize")).thenReturn(Optional.of(skill("skill-1", "summarize")));
        when(dao.deleteById("skill-1")).thenReturn(true);

        assertEquals("skill-1", adapter.findByName("summarize").orElseThrow().id());
        assertTrue(adapter.deleteById("skill-1"));

        when(dao.deleteById("missing")).thenReturn(false);
        assertFalse(adapter.deleteById("missing"));
    }
}
