package com.amsha.core.tool.attempt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import com.amsha.persistence.api.ActionLockScope;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ToolOperationIdentityTest {

    private static final String WS_ID = "ws-prod-1";
    private static final String SESS_ID = "sess-alpha-2";
    private static final String TOOL_NAME = "core_memory_append";
    private static final String ACTION_FP =
            "sha256:v1:9e0b448cea2363b267f6833eeda8f1b35b090dfdc76ac0b732fd3dd3180fba3f";
    private static final String TARGET_FP =
            "target:sha256:v1:1111111111111111111111111111111111111111111111111111111111111111";

    @Test
    @DisplayName("Create unadmitted operation identity allocates valid lowercase UUID operationId")
    void testCreateUnadmittedIdentity() {
        ToolTargetResolution targetRes = ToolTargetResolution.targetResource(TARGET_FP, "core_memory", "user_profile");

        ToolOperationIdentity identity = ToolOperationIdentity.create(WS_ID, SESS_ID, TOOL_NAME, ACTION_FP, targetRes);

        assertEquals(WS_ID, identity.workspaceId());
        assertEquals(SESS_ID, identity.sessionId());
        assertEquals(TOOL_NAME, identity.toolName());
        assertNotNull(identity.operationId());

        // Validate canonical lowercase UUID format
        assertEquals(identity.operationId().toLowerCase(), identity.operationId());
        assertEquals(UUID.fromString(identity.operationId()).toString().toLowerCase(), identity.operationId());

        assertNull(identity.attemptId());
        assertEquals(0, identity.attemptNumber());
        assertEquals(ACTION_FP, identity.actionFingerprint());
        assertEquals(1, identity.fingerprintVersion());
        assertEquals(TARGET_FP, identity.targetFingerprint());
        assertEquals(ActionLockScope.TARGET_RESOURCE, identity.lockScope());
    }

    @Test
    @DisplayName("withAttempt allocates attemptId and attemptNumber while preserving operationId")
    void testWithAttempt() {
        ToolTargetResolution targetRes = ToolTargetResolution.sessionMutations();
        ToolOperationIdentity initial =
                ToolOperationIdentity.create(WS_ID, SESS_ID, "sandbox_exec", ACTION_FP, targetRes);

        String attemptId = ToolOperationIdentity.generateCanonicalUuid();
        ToolOperationIdentity admitted = initial.withAttempt(attemptId, 1);

        assertEquals(initial.operationId(), admitted.operationId());
        assertEquals(attemptId, admitted.attemptId());
        assertEquals(1, admitted.attemptNumber());
        assertEquals(ActionLockScope.SESSION_MUTATIONS, admitted.lockScope());
        assertNull(admitted.targetFingerprint());
    }

    @Test
    @DisplayName("forRetry allocates store-assigned attemptId and attemptNumber")
    void testForRetry() {
        ToolTargetResolution targetRes = ToolTargetResolution.targetResource(TARGET_FP, "core_memory", "user_profile");
        ToolOperationIdentity attempt1 = ToolOperationIdentity.create(WS_ID, SESS_ID, TOOL_NAME, ACTION_FP, targetRes)
                .withAttempt(ToolOperationIdentity.generateCanonicalUuid(), 1);

        String retryAttemptId = ToolOperationIdentity.generateCanonicalUuid();
        ToolOperationIdentity attempt2 = attempt1.forRetry(retryAttemptId, 2);

        assertEquals(attempt1.operationId(), attempt2.operationId());
        assertEquals(retryAttemptId, attempt2.attemptId());
        assertEquals(2, attempt2.attemptNumber());
        assertEquals(attempt1.actionFingerprint(), attempt2.actionFingerprint());
        assertEquals(attempt1.targetFingerprint(), attempt2.targetFingerprint());
        assertEquals(attempt1.lockScope(), attempt2.lockScope());
    }

    @Test
    @DisplayName("Validation rejects inconsistent attempt states (null attemptId with positive number or vice versa)")
    void testInconsistentAttemptStateRejected() {
        String opId = ToolOperationIdentity.generateCanonicalUuid();
        String attId = ToolOperationIdentity.generateCanonicalUuid();

        // Null attemptId with positive attemptNumber
        IllegalArgumentException ex1 = assertThrows(
                IllegalArgumentException.class,
                () -> new ToolOperationIdentity(
                        WS_ID,
                        SESS_ID,
                        TOOL_NAME,
                        opId,
                        null,
                        1,
                        ACTION_FP,
                        1,
                        null,
                        ActionLockScope.SESSION_MUTATIONS));
        assertTrue(ex1.getMessage().contains("attemptNumber must be 0 when attemptId is null"));

        // Present attemptId with 0 attemptNumber
        IllegalArgumentException ex2 = assertThrows(
                IllegalArgumentException.class,
                () -> new ToolOperationIdentity(
                        WS_ID,
                        SESS_ID,
                        TOOL_NAME,
                        opId,
                        attId,
                        0,
                        ACTION_FP,
                        1,
                        null,
                        ActionLockScope.SESSION_MUTATIONS));
        assertTrue(ex2.getMessage().contains("attemptNumber must be positive when attemptId is present"));
    }

    @Test
    @DisplayName("Validation enforces fingerprintVersion matches the actionFingerprint version prefix")
    void testFingerprintVersionMatch() {
        String opId = ToolOperationIdentity.generateCanonicalUuid();

        // Version mismatch: version 2 vs sha256:v1:
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> new ToolOperationIdentity(
                        WS_ID,
                        SESS_ID,
                        TOOL_NAME,
                        opId,
                        null,
                        0,
                        ACTION_FP,
                        2,
                        null,
                        ActionLockScope.SESSION_MUTATIONS));
        assertTrue(
                ex.getMessage().contains("actionFingerprint must start with sha256:v2: to match fingerprintVersion 2"));

        // Non-positive version
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolOperationIdentity(
                        WS_ID,
                        SESS_ID,
                        TOOL_NAME,
                        opId,
                        null,
                        0,
                        ACTION_FP,
                        0,
                        null,
                        ActionLockScope.SESSION_MUTATIONS));
    }

    @Test
    @DisplayName("forRetry enforces store-allocated attemptNumber is greater than current attemptNumber")
    void testForRetryValidation() {
        ToolTargetResolution targetRes = ToolTargetResolution.sessionMutations();
        ToolOperationIdentity attempt1 = ToolOperationIdentity.create(WS_ID, SESS_ID, TOOL_NAME, ACTION_FP, targetRes)
                .withAttempt(ToolOperationIdentity.generateCanonicalUuid(), 1);

        String nextId = ToolOperationIdentity.generateCanonicalUuid();

        // Same attemptNumber
        assertThrows(IllegalArgumentException.class, () -> attempt1.forRetry(nextId, 1));
        // Lower attemptNumber
        assertThrows(IllegalArgumentException.class, () -> attempt1.forRetry(nextId, 0));
        // Null attemptId
        assertThrows(IllegalArgumentException.class, () -> attempt1.forRetry(null, 2));
    }

    @Test
    @DisplayName("UUID validation rejects uppercase or invalid UUIDs")
    void testUuidValidation() {
        String upperUuid = "550E8400-E29B-41D4-A716-446655440000";
        assertThrows(
                IllegalArgumentException.class, () -> ToolOperationIdentity.validateCanonicalUuid(upperUuid, "testId"));

        assertThrows(
                IllegalArgumentException.class,
                () -> ToolOperationIdentity.validateCanonicalUuid("not-a-uuid", "testId"));

        assertThrows(IllegalArgumentException.class, () -> ToolOperationIdentity.validateCanonicalUuid(null, "testId"));

        assertThrows(
                IllegalArgumentException.class, () -> ToolOperationIdentity.validateCanonicalUuid("   ", "testId"));
    }

    @Test
    @DisplayName("Validation enforces prefix rules for action and target fingerprints")
    void testFingerprintPrefixValidation() {
        String badActionFp = "md5:123456";
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolOperationIdentity(
                        WS_ID,
                        SESS_ID,
                        TOOL_NAME,
                        ToolOperationIdentity.generateCanonicalUuid(),
                        null,
                        0,
                        badActionFp,
                        1,
                        null,
                        ActionLockScope.SESSION_MUTATIONS));

        String badTargetFp = "sha256:v1:123456";
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolOperationIdentity(
                        WS_ID,
                        SESS_ID,
                        TOOL_NAME,
                        ToolOperationIdentity.generateCanonicalUuid(),
                        null,
                        0,
                        ACTION_FP,
                        1,
                        badTargetFp,
                        ActionLockScope.TARGET_RESOURCE));

        // Malformed hex in action fingerprint
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolOperationIdentity(
                        WS_ID,
                        SESS_ID,
                        TOOL_NAME,
                        ToolOperationIdentity.generateCanonicalUuid(),
                        null,
                        0,
                        "sha256:v1:not-hex",
                        1,
                        null,
                        ActionLockScope.SESSION_MUTATIONS));

        // Non-lowercase hex in action fingerprint
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolOperationIdentity(
                        WS_ID,
                        SESS_ID,
                        TOOL_NAME,
                        ToolOperationIdentity.generateCanonicalUuid(),
                        null,
                        0,
                        "sha256:v1:9E0B448CEA2363B267F6833EEDA8F1B35B090DFDC76AC0B732FD3DD3180FBA3F",
                        1,
                        null,
                        ActionLockScope.SESSION_MUTATIONS));

        // Malformed hex in target fingerprint
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolOperationIdentity(
                        WS_ID,
                        SESS_ID,
                        TOOL_NAME,
                        ToolOperationIdentity.generateCanonicalUuid(),
                        null,
                        0,
                        ACTION_FP,
                        1,
                        "target:sha256:v1:not-hex",
                        ActionLockScope.TARGET_RESOURCE));
    }

    @Test
    @DisplayName("Validation enforces lock scope and target fingerprint invariants including EXACT_REQUEST")
    void testLockScopeInvariants() {
        // TARGET_RESOURCE requires non-null targetFingerprint
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolOperationIdentity(
                        WS_ID,
                        SESS_ID,
                        TOOL_NAME,
                        ToolOperationIdentity.generateCanonicalUuid(),
                        null,
                        0,
                        ACTION_FP,
                        1,
                        null,
                        ActionLockScope.TARGET_RESOURCE));

        // SESSION_MUTATIONS requires null targetFingerprint
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolOperationIdentity(
                        WS_ID,
                        SESS_ID,
                        TOOL_NAME,
                        ToolOperationIdentity.generateCanonicalUuid(),
                        null,
                        0,
                        ACTION_FP,
                        1,
                        TARGET_FP,
                        ActionLockScope.SESSION_MUTATIONS));

        // EXACT_REQUEST requires null targetFingerprint
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolOperationIdentity(
                        WS_ID,
                        SESS_ID,
                        TOOL_NAME,
                        ToolOperationIdentity.generateCanonicalUuid(),
                        null,
                        0,
                        ACTION_FP,
                        1,
                        TARGET_FP,
                        ActionLockScope.EXACT_REQUEST));

        // EXACT_REQUEST with null targetFingerprint is valid
        ToolOperationIdentity exactReq = new ToolOperationIdentity(
                WS_ID,
                SESS_ID,
                TOOL_NAME,
                ToolOperationIdentity.generateCanonicalUuid(),
                null,
                0,
                ACTION_FP,
                1,
                null,
                ActionLockScope.EXACT_REQUEST);
        assertEquals(ActionLockScope.EXACT_REQUEST, exactReq.lockScope());
        assertNull(exactReq.targetFingerprint());
    }

    @Test
    @DisplayName("ToolTargetResolution enforces cross-field invariants for EXACT_REQUEST and hex digest validation")
    void testTargetResolutionCrossFieldInvariants() {
        // Malformed hex in targetResource throws
        assertThrows(
                IllegalArgumentException.class,
                () -> ToolTargetResolution.targetResource("target:sha256:v1:not-hex", "res", "id"));

        // EXACT_REQUEST rejects non-null targetFingerprint
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolTargetResolution(ActionLockScope.EXACT_REQUEST, TARGET_FP, null, null));

        // EXACT_REQUEST rejects non-null resourceType
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolTargetResolution(ActionLockScope.EXACT_REQUEST, null, "agent_task", null));

        // EXACT_REQUEST rejects non-null resourceIdentity
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolTargetResolution(ActionLockScope.EXACT_REQUEST, null, null, "TASK-1"));

        // exactRequest() factory produces valid resolution
        ToolTargetResolution exactRes = ToolTargetResolution.exactRequest();
        assertEquals(ActionLockScope.EXACT_REQUEST, exactRes.scope());
        assertNull(exactRes.targetFingerprint());
        assertNull(exactRes.resourceType());
        assertNull(exactRes.resourceIdentity());
    }

    @Test
    @DisplayName("Validation rejects negative attempt numbers")
    void testNegativeAttemptNumberRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolOperationIdentity(
                        WS_ID,
                        SESS_ID,
                        TOOL_NAME,
                        ToolOperationIdentity.generateCanonicalUuid(),
                        null,
                        -1,
                        ACTION_FP,
                        1,
                        null,
                        ActionLockScope.SESSION_MUTATIONS));
    }
}
