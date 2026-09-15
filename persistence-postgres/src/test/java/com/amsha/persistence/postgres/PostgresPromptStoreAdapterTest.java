package com.amsha.persistence.postgres;

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

import com.amsha.persistence.api.PromptDto;
import com.amsha.persistence.postgres.jdbi.PromptJdbiDao;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.extension.ExtensionCallback;
import org.jdbi.v3.core.extension.ExtensionConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link PostgresPromptStoreAdapter}'s delegate arms.
 *
 * <p>The adapter has no dedicated test class. Its {@code save} and {@code findActive} paths happen to be
 * exercised by the {@code @QuarkusTest} suite, but the seven remaining methods — including the two
 * {@code delete} scopes and the unfiltered {@code deleteAll}/{@code count} pair — were never called.
 *
 * <p>The delete pair is the one worth having: {@code deleteById} targets one row and
 * {@code deleteByRoleAndKey} targets a whole role/section, so a swap would be destructive rather than
 * merely wrong, and both return a boolean the caller acts on.
 */
class PostgresPromptStoreAdapterTest {

    private Jdbi jdbi;
    private PromptJdbiDao dao;
    private PostgresPromptStoreAdapter adapter;

    @BeforeEach
    void setUp() {
        jdbi = mock(Jdbi.class);
        dao = mock(PromptJdbiDao.class);
        adapter = new PostgresPromptStoreAdapter(jdbi);
    }

    private void givenConsumer() {
        doAnswer(invocation -> {
                    ExtensionConsumer consumer = invocation.getArgument(1);
                    consumer.useExtension(dao);
                    return null;
                })
                .when(jdbi)
                .useExtension(eq(PromptJdbiDao.class), any());
    }

    private void givenExtension() {
        when(jdbi.withExtension(eq(PromptJdbiDao.class), any())).thenAnswer(invocation -> {
            ExtensionCallback callback = invocation.getArgument(1);
            return callback.withExtension(dao);
        });
    }

    private static PromptDto prompt(String id, String roleId) {
        Instant now = Instant.now();
        return new PromptDto(id, roleId, "system", "content", 1, "ACTIVE", 100, null, now, now);
    }

    @Test
    @DisplayName("save forwards the DTO to the DAO")
    void saveForwardsTheDto() {
        givenConsumer();
        PromptDto dto = prompt("prompt-1", "coder");

        adapter.save(dto);

        verify(dao).save(dto);
    }

    @Test
    @DisplayName("findAll, deleteAll and count are passed the DAO's own method references")
    void unfilteredReadsAndCountsDelegateToTheDao() {
        givenExtension();
        when(dao.findAll()).thenReturn(List.of(prompt("prompt-1", "coder")));
        when(dao.deleteAll()).thenReturn(4L);
        when(dao.count()).thenReturn(7L);

        assertEquals(1, adapter.findAll().size());
        assertEquals(4L, adapter.deleteAll());
        assertEquals(7L, adapter.count());
    }

    @Test
    @DisplayName("findById and findByRole carry their argument through")
    void idAndRoleLookupsCarryTheirArgument() {
        givenExtension();
        when(dao.findById("prompt-1")).thenReturn(Optional.of(prompt("prompt-1", "coder")));
        when(dao.findByRole("coder")).thenReturn(List.of(prompt("prompt-1", "coder")));

        assertEquals("prompt-1", adapter.findById("prompt-1").orElseThrow().id());
        assertEquals(1, adapter.findByRole("coder").size());
    }

    @Test
    @DisplayName("deleteById is id-scoped and deleteByRoleAndKey is role-scoped")
    void theTwoDeletesForwardTheirOwnScope() {
        givenExtension();
        when(dao.deleteById("prompt-1")).thenReturn(true);
        when(dao.deleteByRoleAndKey("coder", "system")).thenReturn(false);

        assertTrue(adapter.deleteById("prompt-1"));
        assertFalse(adapter.deleteByRoleAndKey("coder", "system"));
    }
}
