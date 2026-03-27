package com.arcclaw.core.reflex;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class DelegateApprovalGuardTest {

    @Test
    void isDelegateWithConfiguredId() {
        var guard = new DelegateApprovalGuard();
        guard.delegateApproverIds = Optional.of(List.of("admin123", "root"));
        assertTrue(guard.isDelegate("admin123"));
        assertTrue(guard.isDelegate("root"));
        assertFalse(guard.isDelegate("user1"));
    }

    @Test
    void isDelegateWithEmptyConfig() {
        var guard = new DelegateApprovalGuard();
        guard.delegateApproverIds = Optional.empty();
        assertFalse(guard.isDelegate("admin123"));
    }

    @Test
    void isDelegateWithNullUserId() {
        var guard = new DelegateApprovalGuard();
        guard.delegateApproverIds = Optional.of(List.of("admin123"));
        assertFalse(guard.isDelegate(null));
    }
}
