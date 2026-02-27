package com.arcclaw.core.audit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SecurityEventTypeTest {

    @Test
    void testMandatoryFlagOnSecurityEventTypes() {
        // Mandatory — fail-closed
        assertTrue(SecurityEventType.VAULT_ACCESS.isMandatory());
        assertTrue(SecurityEventType.TOOL_INVOKED.isMandatory());
        assertTrue(SecurityEventType.BUDGET_EXCEEDED.isMandatory());
        assertTrue(SecurityEventType.PROMPT_INJECTION_BLOCKED.isMandatory());
        assertTrue(SecurityEventType.APPROVAL_GRANTED.isMandatory());
        assertTrue(SecurityEventType.APPROVAL_DENIED.isMandatory());

        // Non-mandatory — fire-and-forget
        assertFalse(SecurityEventType.REFLEX_COMMAND.isMandatory());
        assertFalse(SecurityEventType.CONTEXT_COMPACTED.isMandatory());
        assertFalse(SecurityEventType.LOOP_FINISH.isMandatory());
        assertFalse(SecurityEventType.LOOP_ERROR.isMandatory());
    }
}
