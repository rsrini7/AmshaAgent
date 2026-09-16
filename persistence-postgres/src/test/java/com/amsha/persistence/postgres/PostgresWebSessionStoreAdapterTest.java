package com.amsha.persistence.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.WebSessionDto;
import com.amsha.persistence.postgres.jdbi.WebSessionJdbiDao;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.extension.ExtensionCallback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link PostgresWebSessionStoreAdapter}'s blank-identifier guards and the four read/write
 * delegates the integration suite does not reach.
 *
 * <p>{@link PostgresIdentityStoreAdapterTest} is a {@code @QuarkusTest} and covers {@code save} and the
 * {@code deleteExpired} null guard, but every identifier it passes is real, so the four {@code isBlank}
 * short-circuits never fire. As in the identity adapter, the guard assertions are only meaningful because
 * that test installs no stubs at all: a blank identifier must return empty (or zero) <em>without</em>
 * opening a connection, and asserting the return value alone would pass even if the guard were deleted.
 */
class PostgresWebSessionStoreAdapterTest {

    private Jdbi jdbi;
    private WebSessionJdbiDao dao;
    private PostgresWebSessionStoreAdapter adapter;

    @BeforeEach
    void setUp() {
        jdbi = mock(Jdbi.class);
        dao = mock(WebSessionJdbiDao.class);
        adapter = new PostgresWebSessionStoreAdapter(jdbi);
    }

    /** Runs the callback the adapter hands to {@code withExtension} against the mocked DAO. */
    private void givenExtension() {
        when(jdbi.withExtension(eq(WebSessionJdbiDao.class), any())).thenAnswer(invocation -> {
            ExtensionCallback callback = invocation.getArgument(1);
            return callback.withExtension(dao);
        });
    }

    private static WebSessionDto session() {
        Instant now = Instant.now();
        return new WebSessionDto("sess-1", "user-1", "digest-1", "csrf-1", now, now, null, null, null, 0, now, now);
    }

    @Test
    @DisplayName("blank identifiers short-circuit without touching the Jdbi")
    void blankIdentifiersShortCircuit() {
        assertTrue(adapter.findByTokenDigest(null).isEmpty());
        assertTrue(adapter.findByTokenDigest("   ").isEmpty());
        assertTrue(adapter.listByUserId(null).isEmpty());
        assertTrue(adapter.listByUserId("").isEmpty());
        assertEquals(0, adapter.deleteById(null));
        assertEquals(0, adapter.deleteById(" "));
        assertEquals(0, adapter.deleteByUserId(null));
        assertEquals(0, adapter.deleteByUserId(""));

        verifyNoInteractions(jdbi);
    }

    @Test
    @DisplayName("findByTokenDigest delegates to the DAO")
    void findByTokenDigestDelegatesToTheDao() {
        givenExtension();
        when(dao.findByTokenDigest("digest-1")).thenReturn(Optional.of(session()));

        assertEquals(
                "sess-1", adapter.findByTokenDigest("digest-1").orElseThrow().sessionId());
    }

    @Test
    @DisplayName("listByUserId delegates to the DAO")
    void listByUserIdDelegatesToTheDao() {
        givenExtension();
        when(dao.listByUserId("user-1")).thenReturn(List.of(session()));

        assertEquals(1, adapter.listByUserId("user-1").size());
    }

    @Test
    @DisplayName("deleteById and deleteByUserId report the DAO's affected-row count")
    void deletesReportTheAffectedRowCount() {
        givenExtension();
        when(dao.deleteById("sess-1")).thenReturn(1);
        when(dao.deleteByUserId("user-1")).thenReturn(3);

        assertEquals(1, adapter.deleteById("sess-1"));
        assertEquals(3, adapter.deleteByUserId("user-1"));
    }
}
