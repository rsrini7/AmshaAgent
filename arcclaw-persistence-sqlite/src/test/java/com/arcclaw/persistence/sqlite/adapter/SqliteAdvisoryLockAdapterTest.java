package com.arcclaw.persistence.sqlite.adapter;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.arcclaw.persistence.api.AdvisoryLockPort;
import com.arcclaw.persistence.sqlite.JdbiRetryWrapper;
import com.arcclaw.persistence.sqlite.SqliteAdvisoryLockAdapter;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SqliteAdvisoryLockAdapterTest {

    @Mock
    Jdbi jdbi;

    @Mock
    JdbiRetryWrapper retryWrapper;

    @InjectMocks
    SqliteAdvisoryLockAdapter adapter;

    @Test
    void implementsAdvisoryLockPort() {
        assertTrue(AdvisoryLockPort.class.isAssignableFrom(SqliteAdvisoryLockAdapter.class));
    }

    @Test
    void tryLock_delegatesToRetryWrapper() {
        when(retryWrapper.withRetry(anyString(), anyInt(), anyLong(), any())).thenReturn(true);

        boolean result = adapter.tryLock("test-lock", 30);

        assertTrue(result);
        verify(retryWrapper).withRetry(eq("try-lock-test-lock"), eq(3), eq(50L), any());
    }

    @Test
    void isLocked_delegatesToRetryWrapper() {
        when(retryWrapper.withRetry(anyString(), anyInt(), anyLong(), any())).thenReturn(false);

        boolean result = adapter.isLocked("test-lock");

        assertTrue(!result);
        verify(retryWrapper).withRetry(eq("is-locked-test-lock"), eq(3), eq(50L), any());
    }
}
