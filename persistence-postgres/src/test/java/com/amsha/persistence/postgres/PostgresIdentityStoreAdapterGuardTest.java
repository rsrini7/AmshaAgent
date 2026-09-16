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

import com.amsha.persistence.postgres.jdbi.IdentityJdbiDao;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.extension.ExtensionCallback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit coverage for {@link PostgresIdentityStoreAdapter}'s input guards and its read-only delegates.
 *
 * <p>Why a separate class: {@link PostgresIdentityStoreAdapterTest} is a {@code @QuarkusTest} against a
 * migrated database, so every call it makes carries a real, non-blank identifier. The {@code isBlank}
 * short-circuits at the top of six methods therefore never fire there, and the handful of listing methods
 * the integration test does not call stay dark too.
 *
 * <p>The guard case is the one that matters: a blank identifier must return empty <em>without</em> opening a
 * connection. Asserting the result alone would pass even if the guard were deleted and the DAO simply
 * returned no rows, so the guard test also asserts that the {@link Jdbi} was never touched — which is only
 * meaningful because that test installs no stubs at all.
 */
class PostgresIdentityStoreAdapterGuardTest {

    private Jdbi jdbi;
    private IdentityJdbiDao dao;
    private PostgresIdentityStoreAdapter adapter;

    @BeforeEach
    void setUp() {
        jdbi = mock(Jdbi.class);
        dao = mock(IdentityJdbiDao.class);
        adapter = new PostgresIdentityStoreAdapter(jdbi);
    }

    /** Runs the callback the adapter hands to {@code withExtension} against the mocked DAO. */
    private void givenExtension() {
        when(jdbi.withExtension(eq(IdentityJdbiDao.class), any())).thenAnswer(invocation -> {
            ExtensionCallback callback = invocation.getArgument(1);
            return callback.withExtension(dao);
        });
    }

    @Test
    @DisplayName("blank and null identifiers short-circuit without touching the Jdbi")
    void blankIdentifiersShortCircuit() {
        assertTrue(adapter.findExternalIdentityByUserId(null, "user-1").isEmpty());
        assertTrue(adapter.findExternalIdentityByUserId("provider-1", "").isEmpty());
        assertTrue(adapter.listExternalIdentitiesByUserId(null).isEmpty());
        assertTrue(adapter.listExternalIdentitiesByUserId("   ").isEmpty());
        assertTrue(adapter.findWorkspaceById(null).isEmpty());
        assertTrue(adapter.findWorkspaceById("").isEmpty());
        assertTrue(adapter.listMembershipsByWorkspaceId(null).isEmpty());
        assertTrue(adapter.listMembershipsByWorkspaceId(" ").isEmpty());
        assertTrue(adapter.listMembershipsByUserId(null).isEmpty());
        assertTrue(adapter.listMembershipsByUserId("").isEmpty());
        assertEquals(0, adapter.deleteMembershipIfNotLastOwner(null, "user-1"));
        assertEquals(0, adapter.deleteMembershipIfNotLastOwner("ws-1", " "));

        verifyNoInteractions(jdbi);
    }

    @Test
    @DisplayName("listUsers and listWorkspaces are unfiltered passes-through")
    void unfilteredListingsDelegateToTheDao() {
        givenExtension();
        when(dao.listUsers()).thenReturn(List.of());
        when(dao.listWorkspaces()).thenReturn(List.of());

        assertTrue(adapter.listUsers().isEmpty());
        assertTrue(adapter.listWorkspaces().isEmpty());
    }

    @Test
    @DisplayName("external-identity and workspace lookups delegate to the DAO")
    void externalIdentityAndWorkspaceLookupsDelegateToTheDao() {
        givenExtension();
        when(dao.findExternalIdentityByUserId("provider-1", "user-1")).thenReturn(Optional.empty());
        when(dao.listExternalIdentitiesByUserId("user-1")).thenReturn(List.of());
        when(dao.findWorkspaceById("ws-1")).thenReturn(Optional.empty());

        assertTrue(adapter.findExternalIdentityByUserId("provider-1", "user-1").isEmpty());
        assertTrue(adapter.listExternalIdentitiesByUserId("user-1").isEmpty());
        assertTrue(adapter.findWorkspaceById("ws-1").isEmpty());
    }

    @Test
    @DisplayName("membership listings and the last-owner guard delegate to the DAO")
    void membershipReadsAndConditionalDeleteDelegateToTheDao() {
        givenExtension();
        when(dao.listMembershipsByWorkspaceId("ws-1")).thenReturn(List.of());
        when(dao.listMembershipsByUserId("user-1")).thenReturn(List.of());
        when(dao.deleteMembershipIfNotLastOwner("ws-1", "user-1")).thenReturn(1);

        assertTrue(adapter.listMembershipsByWorkspaceId("ws-1").isEmpty());
        assertTrue(adapter.listMembershipsByUserId("user-1").isEmpty());
        assertEquals(1, adapter.deleteMembershipIfNotLastOwner("ws-1", "user-1"));
    }
}
