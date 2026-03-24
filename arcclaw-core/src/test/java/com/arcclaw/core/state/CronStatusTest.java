package com.arcclaw.core.state;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CronStatusTest {

    @Test
    void testFromStorageValueRoundTrip() {
        for (CronStatus status : CronStatus.values()) {
            String storage = status.toStorageValue();
            assertEquals(status, CronStatus.fromStorageValue(storage));
        }
    }

    @Test
    void testFromStorageValueCaseInsensitive() {
        assertEquals(CronStatus.ACTIVE, CronStatus.fromStorageValue("active"));
        assertEquals(CronStatus.ACTIVE, CronStatus.fromStorageValue("ACTIVE"));
        assertEquals(CronStatus.ACTIVE, CronStatus.fromStorageValue("Active"));
    }

    @Test
    void testFromStorageValueNullReturnsActive() {
        assertEquals(CronStatus.ACTIVE, CronStatus.fromStorageValue(null));
        assertEquals(CronStatus.ACTIVE, CronStatus.fromStorageValue(""));
        assertEquals(CronStatus.ACTIVE, CronStatus.fromStorageValue("  "));
    }

    @Test
    void testFromStorageValueUnknownThrows() {
        assertThrows(IllegalArgumentException.class, () -> 
            CronStatus.fromStorageValue("UNKNOWN"));
    }
}
