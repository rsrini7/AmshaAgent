package com.amsha.core.util;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class DatabaseSafetyGuardTest {

    @Test
    void ignoresUnrelatedExceptionsIncludingNestedNullMessages() {
        RuntimeException nested = new RuntimeException("network timeout", new RuntimeException((String) null));
        assertFalse(DatabaseSafetyGuard.checkDatabaseFailure(nested));
        assertFalse(DatabaseSafetyGuard.checkDatabaseFailure(new RuntimeException("does not exist")));
        assertFalse(DatabaseSafetyGuard.checkDatabaseFailure(new RuntimeException("sqlite busy")));
        assertFalse(DatabaseSafetyGuard.checkDatabaseFailure(new RuntimeException("Failed to read unrelated path")));
    }
}
