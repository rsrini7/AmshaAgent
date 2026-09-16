package com.amsha.persistence.postgres;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.amsha.persistence.api.ChatMemoryDto;
import com.amsha.persistence.api.ChatMemoryStorePort;
import com.amsha.persistence.postgres.jdbi.ChatMemoryJdbiDao;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.extension.ExtensionCallback;
import org.jdbi.v3.core.extension.ExtensionConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link PostgresChatMemoryStoreAdapter}.
 *
 * <p>No test class mentioned this adapter at all, so all 11 of its lines were uncovered. It is a pure
 * delegate onto {@link ChatMemoryJdbiDao}, with one exception worth pinning down: {@code getTimestamps}
 * ignores its {@code memoryId} argument and always answers with an empty list. That is asserted as a
 * deliberate behaviour (the DAO has no timestamp query) rather than left as an accident — if a timestamp
 * query is ever added, this test should be the one that fails.
 */
class PostgresChatMemoryStoreAdapterTest {

    private Jdbi jdbi;
    private ChatMemoryJdbiDao dao;
    private PostgresChatMemoryStoreAdapter adapter;

    @BeforeEach
    void setUp() {
        jdbi = mock(Jdbi.class);
        dao = mock(ChatMemoryJdbiDao.class);
        adapter = new PostgresChatMemoryStoreAdapter(jdbi);
    }

    private void givenConsumer() {
        doAnswer(invocation -> {
                    ExtensionConsumer consumer = invocation.getArgument(1);
                    consumer.useExtension(dao);
                    return null;
                })
                .when(jdbi)
                .useExtension(eq(ChatMemoryJdbiDao.class), any());
    }

    private void givenExtension() {
        when(jdbi.withExtension(eq(ChatMemoryJdbiDao.class), any())).thenAnswer(invocation -> {
            ExtensionCallback callback = invocation.getArgument(1);
            return callback.withExtension(dao);
        });
    }

    @Test
    void implementsChatMemoryStorePort() {
        assertTrue(ChatMemoryStorePort.class.isAssignableFrom(PostgresChatMemoryStoreAdapter.class));
    }

    @Test
    @DisplayName("the protected no-arg constructor builds an unconfigured instance")
    void theProtectedNoArgConstructorBuildsAnUnconfiguredInstance() {
        assertDoesNotThrow(() -> {
            var ctor = PostgresChatMemoryStoreAdapter.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            ctor.newInstance();
        });
    }

    @Test
    @DisplayName("save and deleteById forward the memory id to the DAO")
    void saveAndDeleteForwardToTheDao() {
        givenConsumer();
        ChatMemoryDto dto = new ChatMemoryDto("mem-1", "[]", "{}");

        adapter.save(dto);
        adapter.deleteById("mem-1");

        verify(dao).save(dto);
        verify(dao).deleteById("mem-1");
    }

    @Test
    @DisplayName("findById passes the DAO's optional straight through")
    void findByIdDelegatesToTheDao() {
        givenExtension();
        when(dao.findById("mem-1")).thenReturn(Optional.of(new ChatMemoryDto("mem-1", "[]", "{}")));

        assertTrue(adapter.findById("mem-1").isPresent());

        when(dao.findById("missing")).thenReturn(Optional.empty());
        assertTrue(adapter.findById("missing").isEmpty());
    }

    @Test
    @DisplayName("getTimestamps answers empty without consulting the DAO")
    void getTimestampsIsAlwaysEmpty() {
        assertTrue(adapter.getTimestamps("mem-1").isEmpty());
        assertTrue(adapter.getTimestamps(null).isEmpty());

        verifyNoInteractions(jdbi);
    }
}
