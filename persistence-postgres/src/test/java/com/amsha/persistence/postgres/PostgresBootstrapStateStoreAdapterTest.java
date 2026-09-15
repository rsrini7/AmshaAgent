package com.amsha.persistence.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import com.amsha.persistence.api.BootstrapStateDto;
import com.amsha.persistence.postgres.jdbi.BootstrapStateJdbiDao;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.extension.ExtensionCallback;
import org.jdbi.v3.core.extension.ExtensionConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit coverage for {@link PostgresBootstrapStateStoreAdapter}.
 *
 * <p>The adapter is a thin delegate: every port method funnels into one {@link BootstrapStateJdbiDao} call
 * whose SQL is a single conditional {@code UPDATE ... WHERE revision = :expectedRevision}. The SQL itself is
 * exercised against embedded Postgres elsewhere; what is asserted here is the translation of the DAO's
 * affected-row count into the port's contract — {@code >0} becomes {@code true} for the boolean methods, and
 * {@code reserveFirstOwner}'s zero-row case must short-circuit to {@link Optional#empty()} rather than fall
 * through to a state read.
 */
class PostgresBootstrapStateStoreAdapterTest {

    private Jdbi jdbi;
    private BootstrapStateJdbiDao dao;
    private PostgresBootstrapStateStoreAdapter adapter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbi = mock(Jdbi.class);
        dao = mock(BootstrapStateJdbiDao.class);
        when(jdbi.withExtension(eq(BootstrapStateJdbiDao.class), any())).thenAnswer(invocation -> {
            ExtensionCallback callback = invocation.getArgument(1);
            return callback.withExtension(dao);
        });
        adapter = new PostgresBootstrapStateStoreAdapter(jdbi);
    }

    private static BootstrapStateDto state(int revision) {
        Instant now = Instant.now();
        return new BootstrapStateDto(
                "bootstrap", BootstrapStateDto.STATUS_CLAIMING, "owner-1", "ws-1", null, null, null, revision, now);
    }

    private void givenSaveConsumer() {
        doAnswer(invocation -> {
                    ExtensionConsumer consumer = invocation.getArgument(1);
                    consumer.useExtension(dao);
                    return null;
                })
                .when(jdbi)
                .useExtension(eq(BootstrapStateJdbiDao.class), any());
    }

    @Test
    @DisplayName("save hands the DTO straight to the DAO")
    void saveDelegatesToTheDao() {
        givenSaveConsumer();
        BootstrapStateDto state = state(1);

        adapter.save(state);

        verify(dao).save(state);
    }

    @Test
    @DisplayName("getState returns whatever the DAO reports, including empty")
    void getStateDelegatesToTheDao() {
        when(dao.getState()).thenReturn(Optional.of(state(3)));

        assertEquals(3, adapter.getState().orElseThrow().revision());

        when(dao.getState()).thenReturn(Optional.empty());
        assertTrue(adapter.getState().isEmpty());
    }

    @Test
    @DisplayName("reserveFirstOwner returns the new revision when the row was claimed")
    void reserveFirstOwnerReturnsTheNewRevision() {
        when(dao.reserveFirstOwner(eq("owner-1"), eq("ws-1"), any())).thenReturn(1);
        when(dao.getState()).thenReturn(Optional.of(state(2)));

        assertEquals(Optional.of(2), adapter.reserveFirstOwner("owner-1", "ws-1", Instant.now()));
    }

    @Test
    @DisplayName("reserveFirstOwner returns empty on a lost race without reading the state back")
    void reserveFirstOwnerReturnsEmptyWhenNoRowWasClaimed() {
        when(dao.reserveFirstOwner(eq("owner-1"), eq("ws-1"), any())).thenReturn(0);

        assertTrue(adapter.reserveFirstOwner("owner-1", "ws-1", Instant.now()).isEmpty());
        verify(dao, never()).getState();
    }

    @Test
    @DisplayName("reserveFirstOwner returns empty when the row was claimed but the state is unreadable")
    void reserveFirstOwnerReturnsEmptyWhenTheStateCannotBeRead() {
        when(dao.reserveFirstOwner(eq("owner-1"), eq("ws-1"), any())).thenReturn(1);
        when(dao.getState()).thenReturn(Optional.empty());

        assertTrue(adapter.reserveFirstOwner("owner-1", "ws-1", Instant.now()).isEmpty());
    }

    @Test
    @DisplayName("recordCredentialIssued reports whether the revision-matched update landed")
    void recordCredentialIssuedReflectsTheAffectedRowCount() {
        Instant now = Instant.now();
        when(dao.recordCredentialIssued(2, "digest-1", now)).thenReturn(1);

        assertTrue(adapter.recordCredentialIssued(2, "digest-1", now));

        when(dao.recordCredentialIssued(1, "digest-1", now)).thenReturn(0);
        assertFalse(adapter.recordCredentialIssued(1, "digest-1", now));
    }

    @Test
    @DisplayName("confirmClaim reports whether the owner-matched update landed")
    void confirmClaimReflectsTheAffectedRowCount() {
        Instant now = Instant.now();
        when(dao.confirmClaim(3, "owner-1", now)).thenReturn(1);

        assertTrue(adapter.confirmClaim(3, "owner-1", now));

        when(dao.confirmClaim(3, "someone-else", now)).thenReturn(0);
        assertFalse(adapter.confirmClaim(3, "someone-else", now));
    }

    @Test
    @DisplayName("rollbackClaiming reports whether the revision-matched update landed")
    void rollbackClaimingReflectsTheAffectedRowCount() {
        Instant now = Instant.now();
        when(dao.rollbackClaiming(2, now)).thenReturn(1);

        assertTrue(adapter.rollbackClaiming(2, now));

        when(dao.rollbackClaiming(9, now)).thenReturn(0);
        assertFalse(adapter.rollbackClaiming(9, now));
    }

    @Test
    @DisplayName("replaceCredential reports whether the revision-matched update landed")
    void replaceCredentialReflectsTheAffectedRowCount() {
        Instant issuedAt = Instant.now();
        Instant updatedAt = issuedAt.plusSeconds(60);
        when(dao.replaceCredential(4, "digest-2", issuedAt, updatedAt)).thenReturn(1);

        assertTrue(adapter.replaceCredential(4, "digest-2", issuedAt, updatedAt));

        when(dao.replaceCredential(4, "digest-3", issuedAt, updatedAt)).thenReturn(0);
        assertFalse(adapter.replaceCredential(4, "digest-3", issuedAt, updatedAt));
    }
}
