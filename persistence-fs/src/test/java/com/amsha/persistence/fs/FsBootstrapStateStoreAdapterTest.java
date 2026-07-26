package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;

import com.amsha.persistence.api.BootstrapStateDto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FsBootstrapStateStoreAdapterTest {

    @Mock
    FilesystemFileStore store;

    @InjectMocks
    FsBootstrapStateStoreAdapter adapter;

    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.now();
    }

    @Test
    void testSaveWhenEmpty() {
        when(store.readOptional(any(), eq(BootstrapStateDto.class))).thenReturn(Optional.empty());
        BootstrapStateDto state = new BootstrapStateDto(
                "bootstrap", BootstrapStateDto.STATUS_UNCLAIMED, null, null, null, null, null, 0, now);
        adapter.save(state);
        verify(store).write(any(), eq(state));
    }

    @Test
    void testReserveFirstOwnerSuccess() {
        when(store.readOptional(any(), eq(BootstrapStateDto.class))).thenReturn(Optional.empty());

        Optional<Integer> rev = adapter.reserveFirstOwner("owner-1", "ws-1", now);
        assertTrue(rev.isPresent());
        assertEquals(1, rev.get());
        verify(store).write(any(), any(BootstrapStateDto.class));
    }

    @Test
    void testReserveFirstOwnerFailsWhenAlreadyClaimed() {
        BootstrapStateDto claimed = new BootstrapStateDto(
                "bootstrap", BootstrapStateDto.STATUS_CLAIMED, "owner-1", "ws-1", "digest", now, now, 2, now);
        when(store.readOptional(any(), eq(BootstrapStateDto.class))).thenReturn(Optional.of(claimed));

        Optional<Integer> rev = adapter.reserveFirstOwner("owner-2", "ws-2", now);
        assertTrue(rev.isEmpty());
    }

    @Test
    void testRecordCredentialIssuedSuccessAndMismatch() {
        BootstrapStateDto claiming = new BootstrapStateDto(
                "bootstrap", BootstrapStateDto.STATUS_CLAIMING, "owner-1", "ws-1", null, null, null, 1, now);
        when(store.readOptional(any(), eq(BootstrapStateDto.class))).thenReturn(Optional.of(claiming));

        assertFalse(adapter.recordCredentialIssued(99, "digest-1", now));
        assertTrue(adapter.recordCredentialIssued(1, "digest-1", now));
        verify(store).write(any(), any(BootstrapStateDto.class));
    }

    @Test
    void testConfirmClaimSuccessAndMismatch() {
        BootstrapStateDto issued = new BootstrapStateDto(
                "bootstrap",
                BootstrapStateDto.STATUS_CREDENTIAL_ISSUED,
                "owner-1",
                "ws-1",
                "digest-1",
                now,
                null,
                2,
                now);
        when(store.readOptional(any(), eq(BootstrapStateDto.class))).thenReturn(Optional.of(issued));

        assertFalse(adapter.confirmClaim(2, "wrong-owner", now));
        assertTrue(adapter.confirmClaim(2, "owner-1", now));
        verify(store).write(any(), any(BootstrapStateDto.class));
    }

    @Test
    void testRollbackClaimingSuccess() {
        BootstrapStateDto claiming = new BootstrapStateDto(
                "bootstrap", BootstrapStateDto.STATUS_CLAIMING, "owner-1", "ws-1", null, null, null, 1, now);
        when(store.readOptional(any(), eq(BootstrapStateDto.class))).thenReturn(Optional.of(claiming));

        assertTrue(adapter.rollbackClaiming(1, now));
        verify(store).write(any(), any(BootstrapStateDto.class));
    }

    @Test
    void testReplaceCredentialSuccess() {
        BootstrapStateDto issued = new BootstrapStateDto(
                "bootstrap",
                BootstrapStateDto.STATUS_CREDENTIAL_ISSUED,
                "owner-1",
                "ws-1",
                "digest-1",
                now,
                null,
                2,
                now);
        when(store.readOptional(any(), eq(BootstrapStateDto.class))).thenReturn(Optional.of(issued));

        assertTrue(adapter.replaceCredential(2, "new-digest", now, now));
        verify(store).write(any(), any(BootstrapStateDto.class));
    }
}
