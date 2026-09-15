package com.amsha.persistence.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.amsha.persistence.postgres.jdbi.IdentityInvitationJdbiDao;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.extension.ExtensionCallback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link PostgresIdentityInvitationStoreAdapter}'s blank-identifier guards and the three
 * delegates the integration suite does not reach.
 *
 * <p>As in the identity and web-session adapters, the guard assertions are only meaningful because this test
 * installs no stubs at all: a blank identifier must return empty (or zero) <em>without</em> opening a
 * connection, and asserting the return value alone would pass even if the guard were deleted.
 */
class PostgresIdentityInvitationStoreAdapterTest {

    private Jdbi jdbi;
    private IdentityInvitationJdbiDao dao;
    private PostgresIdentityInvitationStoreAdapter adapter;

    @BeforeEach
    void setUp() {
        jdbi = mock(Jdbi.class);
        dao = mock(IdentityInvitationJdbiDao.class);
        adapter = new PostgresIdentityInvitationStoreAdapter(jdbi);
    }

    /** Runs the callback the adapter hands to {@code withExtension} against the mocked DAO. */
    private void givenExtension() {
        when(jdbi.withExtension(eq(IdentityInvitationJdbiDao.class), any())).thenAnswer(invocation -> {
            ExtensionCallback callback = invocation.getArgument(1);
            return callback.withExtension(dao);
        });
    }

    @Test
    @DisplayName("blank identifiers short-circuit without touching the Jdbi")
    void blankIdentifiersShortCircuit() {
        assertTrue(adapter.findByTokenDigest(null).isEmpty());
        assertTrue(adapter.findByTokenDigest("   ").isEmpty());
        assertTrue(adapter.listByWorkspaceId(null).isEmpty());
        assertTrue(adapter.listByWorkspaceId("").isEmpty());
        assertEquals(0, adapter.deleteById(null));
        assertEquals(0, adapter.deleteById(" "));

        verifyNoInteractions(jdbi);
    }

    @Test
    @DisplayName("findByTokenDigest passes the DAO's optional straight through")
    void findByTokenDigestDelegatesToTheDao() {
        givenExtension();
        when(dao.findByTokenDigest("digest-1")).thenReturn(Optional.empty());

        assertTrue(adapter.findByTokenDigest("digest-1").isEmpty());
    }

    @Test
    @DisplayName("listByWorkspaceId delegates to the DAO")
    void listByWorkspaceIdDelegatesToTheDao() {
        givenExtension();
        when(dao.listByWorkspaceId("ws-1")).thenReturn(List.of());

        assertTrue(adapter.listByWorkspaceId("ws-1").isEmpty());
    }

    @Test
    @DisplayName("deleteById reports the DAO's affected-row count")
    void deleteByIdReportsTheAffectedRowCount() {
        givenExtension();
        when(dao.deleteById("inv-1")).thenReturn(1);

        assertEquals(1, adapter.deleteById("inv-1"));
    }
}
