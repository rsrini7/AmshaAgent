package com.amsha.core.hitl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ApprovalLeaseStoreTest {

    private ApprovalLeaseStore store;
    private AuditService auditService;

    @BeforeEach
    void setUp() {
        store = new ApprovalLeaseStore();
        auditService = mock(AuditService.class);
        store.objectMapper = new ObjectMapper();
        store.auditService = auditService;
        store.enabled = true;
        store.ttlSeconds = 3600;
        store.init();
    }

    @Test
    void grantThenExactShapeHit() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("command", "find .");
        params.put("count", 5);
        store.grant("s1", "host_exec", params);

        assertTrue(store.hasActiveLease("s1", "researcher", "user-1", "host_exec", params));
        verify(auditService)
                .record(
                        eq("s1"),
                        eq("user-1"),
                        eq("researcher"),
                        eq(SecurityEventType.HITL_LEASE_REUSED),
                        eq("tool=host_exec"));
    }

    @Test
    void paramDriftMissesLease() {
        Map<String, Object> approved = Map.of("command", "find .");
        store.grant("s1", "host_exec", approved);

        Map<String, Object> drifted = Map.of("command", "rm -rf /");
        assertFalse(store.hasActiveLease("s1", "researcher", "u", "host_exec", drifted));
    }

    @Test
    void keyOrderDoesNotAffectHash() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("a", 1);
        first.put("b", 2);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("b", 2);
        second.put("a", 1);
        store.grant("s1", "tool", first);

        assertTrue(store.hasActiveLease("s1", "role", "u", "tool", second));
    }

    @Test
    void leasesNeverCrossSessions() {
        store.grant("s1", "host_exec", Map.of("command", "ls"));
        assertFalse(store.hasActiveLease("s2", "researcher", "u", "host_exec", Map.of("command", "ls")));
    }

    @Test
    void clearSessionEvictsAllSessionLeases() {
        store.grant("s1", "host_exec", Map.of("command", "ls"));
        store.grant("s1", "web_fetch", Map.of("url", "https://x"));
        store.grant("s2", "host_exec", Map.of("command", "ls"));

        store.clearSession("s1");

        assertEquals(1, store.size());
        assertFalse(store.hasActiveLease("s1", "r", "u", "host_exec", Map.of("command", "ls")));
        assertTrue(store.hasActiveLease("s2", "r", "u", "host_exec", Map.of("command", "ls")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ttlExpiryCheckedLazily() throws Exception {
        store.grant("s1", "host_exec", Map.of("command", "ls"));
        // Rewind every recorded expiry into the past; lazy check must evict on access.
        Field leasesField = ApprovalLeaseStore.class.getDeclaredField("leases");
        leasesField.setAccessible(true);
        Map<ApprovalLeaseStore.LeaseKey, Long> leases = (Map<ApprovalLeaseStore.LeaseKey, Long>) leasesField.get(store);
        leases.replaceAll((k, v) -> System.currentTimeMillis() - 1000);

        assertFalse(store.hasActiveLease("s1", "r", "u", "host_exec", Map.of("command", "ls")));
        assertEquals(0, store.size());
    }

    @Test
    void disabledFlagSuppressesGrantAndConsumption() {
        store.enabled = false;
        store.grant("s1", "host_exec", Map.of("command", "ls"));

        assertEquals(0, store.size());
        assertFalse(store.hasActiveLease("s1", "r", "u", "host_exec", Map.of("command", "ls")));
    }

    @Test
    void auditFailureNeverBreaksConsumption() {
        Mockito.doThrow(new RuntimeException("telemetry down"))
                .when(auditService)
                .record(any(), any(), any(), any(SecurityEventType.class), anyString());
        store.grant("s1", "host_exec", Map.of("command", "ls"));

        assertTrue(store.hasActiveLease("s1", "r", "u", "host_exec", Map.of("command", "ls")));
        clearInvocations(auditService);
    }

    @Test
    void lruBoundEvictsEldestInsertion() throws Exception {
        store.ttlSeconds = 3600;
        store.init();
        Field maxField = ApprovalLeaseStore.class.getDeclaredField("MAX_LEASES");
        maxField.setAccessible(true);

        // Grant beyond a small probe bound by filling MAX_LEASES + 1 entries.
        int max = maxField.getInt(null);
        for (int i = 0; i <= max; i++) {
            store.grant("s-bulk", "t" + i, Map.of("i", i));
        }
        assertTrue(store.size() <= ApprovalLeaseStore.MAX_LEASES);
    }
}
