package com.amsha.persistence.postgres;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import com.amsha.persistence.postgres.jdbi.ApiCredentialJdbiDao;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.extension.ExtensionCallback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link PostgresApiCredentialStoreAdapter#listByUserId}.
 *
 * <p>The adapter is covered elsewhere by {@link PostgresIdentityStoreAdapterTest}, a {@code @QuarkusTest}
 * that exercises credentials with real identifiers — so the blank-userId short-circuit and the listing it
 * guards are the two lines left dark. The guard assertion is only meaningful because this test installs no
 * stubs: a blank identifier must return empty <em>without</em> opening a connection.
 */
class PostgresApiCredentialStoreAdapterTest {

    private Jdbi jdbi;
    private ApiCredentialJdbiDao dao;
    private PostgresApiCredentialStoreAdapter adapter;

    @BeforeEach
    void setUp() {
        jdbi = mock(Jdbi.class);
        dao = mock(ApiCredentialJdbiDao.class);
        adapter = new PostgresApiCredentialStoreAdapter(jdbi);
    }

    @Test
    @DisplayName("a blank userId short-circuits without touching the Jdbi")
    void blankUserIdShortCircuits() {
        assertTrue(adapter.listByUserId(null).isEmpty());
        assertTrue(adapter.listByUserId("   ").isEmpty());

        verifyNoInteractions(jdbi);
    }

    @Test
    @DisplayName("listByUserId delegates to the DAO")
    void listByUserIdDelegatesToTheDao() {
        when(jdbi.withExtension(eq(ApiCredentialJdbiDao.class), any())).thenAnswer(invocation -> {
            ExtensionCallback callback = invocation.getArgument(1);
            return callback.withExtension(dao);
        });
        when(dao.listByUserId("user-1")).thenReturn(List.of());

        assertTrue(adapter.listByUserId("user-1").isEmpty());
    }
}
