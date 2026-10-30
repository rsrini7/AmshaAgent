package com.amsha.core.audit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SecurityEventTypeTest {

    @Test
    void testMandatoryFlagOnSecurityEventTypes() {
        // Mandatory - fail-closed
        assertTrue(SecurityEventType.VAULT_ACCESS.isMandatory());
        assertTrue(SecurityEventType.TOOL_INVOKED.isMandatory());
        assertTrue(SecurityEventType.BUDGET_EXCEEDED.isMandatory());
        assertTrue(SecurityEventType.PROMPT_INJECTION_BLOCKED.isMandatory());
        assertTrue(SecurityEventType.APPROVAL_GRANTED.isMandatory());
        assertTrue(SecurityEventType.APPROVAL_DENIED.isMandatory());
        assertTrue(SecurityEventType.MCP_SERVER_CONNECTED.isMandatory());
        assertTrue(SecurityEventType.MCP_SERVER_DISCONNECTED.isMandatory());
        assertTrue(SecurityEventType.MCP_CONNECTION_REQUESTED.isMandatory());
        assertTrue(SecurityEventType.MCP_TOOL_POLICY_ENABLED.isMandatory());
        assertTrue(SecurityEventType.MCP_CONNECTION_VALIDATION_FAILED.isMandatory());
        assertTrue(SecurityEventType.MCP_CREDENTIAL_CONFIGURED.isMandatory());
        assertTrue(SecurityEventType.MCP_CREDENTIAL_ROTATED.isMandatory());
        assertTrue(SecurityEventType.MCP_CREDENTIAL_REMOVED.isMandatory());
        assertTrue(SecurityEventType.MCP_CREDENTIAL_CLEANUP_FAILED.isMandatory());
        assertTrue(SecurityEventType.CLASSIFIER_RUNTIME_CHANGE_REQUESTED.isMandatory());
        assertTrue(SecurityEventType.CLASSIFIER_RUNTIME_CHANGE_ACCEPTED.isMandatory());
        assertTrue(SecurityEventType.CLASSIFIER_RUNTIME_CHANGE_REJECTED.isMandatory());
        assertTrue(SecurityEventType.ONNX_MODEL_UNLOAD_REQUESTED.isMandatory());
        assertTrue(SecurityEventType.SESSION_INSPECTED.isMandatory());
        assertTrue(SecurityEventType.DATABASE_QUERY_FAILED.isMandatory());
        assertTrue(SecurityEventType.PROVIDER_EGRESS_VALIDATED.isMandatory());
        assertTrue(SecurityEventType.EXTERNAL_SOURCE_EGRESS_REQUESTED.isMandatory());
        assertTrue(SecurityEventType.EXTERNAL_SOURCE_INDEX_MUTATED.isMandatory());
        assertTrue(SecurityEventType.OUTBOUND_EGRESS_DENIED.isMandatory());

        // Non-mandatory - fire-and-forget
        assertFalse(SecurityEventType.REFLEX_COMMAND.isMandatory());
        assertFalse(SecurityEventType.CONTEXT_COMPACTED.isMandatory());
        assertFalse(SecurityEventType.LOOP_FINISH.isMandatory());
        assertFalse(SecurityEventType.LOOP_ERROR.isMandatory());
        assertFalse(SecurityEventType.ONBOARDING_EXTENSION_SHOWN.isMandatory());
        assertFalse(SecurityEventType.ONBOARDING_EXTENSION_SKIPPED.isMandatory());
        assertFalse(SecurityEventType.ONBOARDING_EXTENSION_VALIDATED.isMandatory());
        assertFalse(SecurityEventType.ONBOARDING_EXTENSION_COMMITTED.isMandatory());
        assertFalse(SecurityEventType.ONBOARDING_EXTENSION_FAILED.isMandatory());
    }
}
