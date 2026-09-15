package com.amsha.persistence.postgres;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.CoreMemoryBlockDto;
import com.amsha.persistence.api.VersionConflictException;
import com.amsha.persistence.postgres.jdbi.CoreMemoryJdbiDao;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.HandleCallback;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.extension.ExtensionCallback;
import org.jdbi.v3.core.extension.ExtensionConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PostgresCoreMemoryStoreTest {

    @Mock
    Jdbi jdbi;

    @Mock
    Handle handle;

    @Mock
    CoreMemoryJdbiDao dao;

    private PostgresCoreMemoryStore store;

    @BeforeEach
    void setUp() {
        store = new PostgresCoreMemoryStore(jdbi);
    }

    @Test
    void testReadAndGet() {
        when(jdbi.withExtension(eq(CoreMemoryJdbiDao.class), any())).thenAnswer(inv -> {
            ExtensionCallback callback = inv.getArgument(1);
            return callback.withExtension(dao);
        });

        when(dao.findByUser("usr-1")).thenReturn(List.of());
        assertTrue(store.read("usr-1").isEmpty());

        when(dao.findByKey("usr-1", "user_profile")).thenReturn(Optional.empty());
        assertFalse(store.get("usr-1", "user_profile").isPresent());
    }

    @Test
    void testUpsertAndDelete() {
        when(jdbi.inTransaction(any())).thenAnswer(inv -> {
            HandleCallback callback = inv.getArgument(0);
            return callback.withHandle(handle);
        });
        when(handle.attach(CoreMemoryJdbiDao.class)).thenReturn(dao);

        doAnswer(inv -> {
                    ExtensionConsumer consumer = inv.getArgument(1);
                    consumer.useExtension(dao);
                    return null;
                })
                .when(jdbi)
                .useExtension(eq(CoreMemoryJdbiDao.class), any());

        when(dao.upsert(anyString(), eq("usr-1"), eq("user_profile"), eq("content"), eq(1), any()))
                .thenReturn(1);

        assertDoesNotThrow(() -> store.upsert("usr-1", "user_profile", "content", 1));
        verify(dao).upsert(anyString(), eq("usr-1"), eq("user_profile"), eq("content"), eq(1), any());

        store.delete("usr-1", "user_profile");
        verify(dao).delete("usr-1", "user_profile");
    }

    @Test
    void theProtectedNoArgConstructorBuildsAnUnconfiguredInstance() {
        // Same package as the store, so the protected constructor is reachable without reflection.
        assertDoesNotThrow(() -> new PostgresCoreMemoryStore());
    }

    @Test
    void testUpsertReportsTheStoredVersionOnConflict() {
        givenRejectedUpsert();
        when(dao.findByKey("usr-1", "user_profile")).thenReturn(Optional.of(block(4)));

        VersionConflictException thrown =
                assertThrows(VersionConflictException.class, () -> store.upsert("usr-1", "user_profile", "content", 1));

        assertEquals("user_profile", thrown.blockKey());
        assertEquals(1, thrown.expectedVersion());
        // The read-back exists so the caller learns the version it should have sent.
        assertEquals(4, thrown.actualVersion());
    }

    @Test
    void testUpsertReportsMinusOneWhenTheBlockDisappeared() {
        givenRejectedUpsert();
        when(dao.findByKey("usr-1", "user_profile")).thenReturn(Optional.empty());

        VersionConflictException thrown =
                assertThrows(VersionConflictException.class, () -> store.upsert("usr-1", "user_profile", "content", 1));

        assertEquals(-1, thrown.actualVersion(), "a row deleted between the update and the read has no version");
    }

    /** Arranges an optimistic-lock miss: the update affects no rows, then the store reads the real version. */
    private void givenRejectedUpsert() {
        when(jdbi.inTransaction(any())).thenAnswer(inv -> {
            HandleCallback callback = inv.getArgument(0);
            return callback.withHandle(handle);
        });
        when(handle.attach(CoreMemoryJdbiDao.class)).thenReturn(dao);
        when(dao.upsert(anyString(), eq("usr-1"), eq("user_profile"), eq("content"), eq(1), any()))
                .thenReturn(0);
        when(jdbi.withExtension(eq(CoreMemoryJdbiDao.class), any())).thenAnswer(inv -> {
            ExtensionCallback callback = inv.getArgument(1);
            return callback.withExtension(dao);
        });
    }

    private static CoreMemoryBlockDto block(int version) {
        Instant now = Instant.now();
        return new CoreMemoryBlockDto("block-1", "usr-1", "user_profile", "other content", version, now, now);
    }
}
