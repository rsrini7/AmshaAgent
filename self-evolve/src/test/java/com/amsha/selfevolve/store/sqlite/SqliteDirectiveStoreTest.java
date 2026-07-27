package com.amsha.selfevolve.store.sqlite;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import com.amsha.selfevolve.api.DirectiveState;
import com.amsha.selfevolve.api.SynthesizedDirective;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.extension.ExtensionCallback;
import org.jdbi.v3.core.extension.ExtensionConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SqliteDirectiveStoreTest {

    @Mock
    Jdbi jdbi;

    @Mock
    DirectiveJdbiDao dao;

    private SqliteDirectiveStore store;

    @BeforeEach
    void setUp() {
        store = new SqliteDirectiveStore();
        store.jdbi = jdbi;
    }

    @Test
    void testInsertAndUpdate() {
        when(jdbi.withExtension(eq(DirectiveJdbiDao.class), any())).thenAnswer(inv -> {
            ExtensionCallback callback = inv.getArgument(1);
            return callback.withExtension(dao);
        });
        doAnswer(inv -> {
                    ExtensionConsumer consumer = inv.getArgument(1);
                    consumer.useExtension(dao);
                    return null;
                })
                .when(jdbi)
                .useExtension(eq(DirectiveJdbiDao.class), any());

        SynthesizedDirective dir = SynthesizedDirective.create("usr-1", "rule text", 0.9, "src-1", "hash-1");

        when(dao.insert(dir)).thenReturn(1);
        assertEquals(1, store.insert(dir));

        assertDoesNotThrow(() -> store.update(dir));
        verify(dao).update(dir);

        when(dao.deleteById(dir.id())).thenReturn(1);
        assertTrue(store.delete(dir.id()));

        when(dao.purgeExpired(30)).thenReturn(2);
        assertEquals(2, store.purgeExpired(30));
    }

    @Test
    void testQueries() {
        when(jdbi.withExtension(eq(DirectiveJdbiDao.class), any())).thenAnswer(inv -> {
            ExtensionCallback callback = inv.getArgument(1);
            return callback.withExtension(dao);
        });

        when(dao.getInjectable("usr-1", 10)).thenReturn(List.of());
        assertTrue(store.getInjectable("usr-1", 10).isEmpty());

        when(dao.getAll("usr-1", 10)).thenReturn(List.of());
        assertTrue(store.getAll("usr-1", 10).isEmpty());

        when(dao.getById("d-1")).thenReturn(null);
        assertNull(store.getById("d-1"));

        when(dao.getByState("ACTIVE", 10)).thenReturn(List.of());
        assertTrue(store.getByState(DirectiveState.ACTIVE, 10).isEmpty());
    }
}
