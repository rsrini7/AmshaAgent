package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Set;

import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CronStatus;
import com.amsha.persistence.api.ToolDecision;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * Wire compatibility for the state vocabulary after its move from
 * {@code com.amsha.core.state} to {@code com.amsha.persistence.api}.
 *
 * <p>The expected values below are FIXED LEGACY FIXTURES captured from the pre-move
 * format. They are intentionally literal: if an enum constant is added, renamed, or
 * its serialization changes, these tests fail and force a migration review before any
 * persisted document becomes unreadable.
 */
public class StateVocabularyWireCompatibilityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Legacy checkpoint statuses as persisted before the package move (order-insensitive). */
    private static final Set<String> LEGACY_CHECKPOINT_STATUSES = Set.of(
            "NOT_STARTED",
            "IN_PROGRESS",
            "AWAITING_APPROVAL",
            "DENIED",
            "COMPLETED",
            "FAILED",
            "LOOP_EXCEEDED",
            "COMPROMISED",
            "AWAITING_RETRY",
            "RESUMING",
            "APPROVED",
            "ARCHIVED",
            "ORPHANED");

    /** Legacy cron statuses as persisted before the package move. */
    private static final Set<String> LEGACY_CRON_STATUSES =
            Set.of("ACTIVE", "RUNNING", "COMPLETED", "FAILED", "AWAITING_APPROVAL", "SKIPPED");

    /** Legacy tool decisions as embedded in checkpoint documents. */
    private static final Set<String> LEGACY_TOOL_DECISIONS = Set.of("PENDING", "APPROVED", "DENIED");

    @Test
    public void checkpointEnumNamesAreUnchangedAgainstLegacyFixture() {
        assertEquals(
                LEGACY_CHECKPOINT_STATUSES,
                Set.of(Arrays.stream(CheckpointStatus.values()).map(Enum::name).toArray(String[]::new)),
                "CheckpointStatus constants drifted from the persisted legacy set");
    }

    @Test
    public void checkpointJsonSerializationMatchesLegacyLiteralFormat() throws Exception {
        // Spot-lock representative constants against byte-exact legacy JSON.
        assertEquals("\"IN_PROGRESS\"", objectMapper.writeValueAsString(CheckpointStatus.IN_PROGRESS));
        assertEquals("\"AWAITING_APPROVAL\"", objectMapper.writeValueAsString(CheckpointStatus.AWAITING_APPROVAL));
        assertEquals("\"ORPHANED\"", objectMapper.writeValueAsString(CheckpointStatus.ORPHANED));
        assertEquals(CheckpointStatus.COMPLETED, objectMapper.readValue("\"COMPLETED\"", CheckpointStatus.class));
        assertEquals(CheckpointStatus.DENIED, objectMapper.readValue("\"DENIED\"", CheckpointStatus.class));
    }

    @Test
    public void cronEnumNamesAndStorageValuesMatchLegacyFixture() throws Exception {
        assertEquals(
                LEGACY_CRON_STATUSES,
                Set.of(Arrays.stream(CronStatus.values()).map(Enum::name).toArray(String[]::new)),
                "CronStatus constants drifted from the persisted legacy set");
        assertEquals("ACTIVE", new CronStatus[] {CronStatus.ACTIVE}[0].toStorageValue());
        assertEquals("AWAITING_APPROVAL", CronStatus.AWAITING_APPROVAL.toStorageValue());
        assertEquals("SKIPPED", CronStatus.SKIPPED.toStorageValue());
        assertEquals("\"FAILED\"", objectMapper.writeValueAsString(CronStatus.FAILED));
        assertEquals(CronStatus.RUNNING, CronStatus.fromStorageValue("RUNNING"));
        // Legacy tolerant-parsing behavior: case-insensitive lookup, blank/null default ACTIVE.
        assertEquals(CronStatus.ACTIVE, CronStatus.fromStorageValue(null));
        assertEquals(CronStatus.ACTIVE, CronStatus.fromStorageValue("   "));
        assertEquals(CronStatus.COMPLETED, CronStatus.fromStorageValue("completed"));
    }

    @Test
    public void toolDecisionNamesMatchLegacyFixtureAndRoundTrip() throws Exception {
        assertEquals(
                LEGACY_TOOL_DECISIONS,
                Set.of(Arrays.stream(ToolDecision.values()).map(Enum::name).toArray(String[]::new)),
                "ToolDecision constants drifted from the persisted legacy set");
        assertEquals("\"PENDING\"", objectMapper.writeValueAsString(ToolDecision.PENDING));
        assertEquals("\"APPROVED\"", objectMapper.writeValueAsString(ToolDecision.APPROVED));
        assertEquals(ToolDecision.DENIED, objectMapper.readValue("\"DENIED\"", ToolDecision.class));
    }

    @Test
    public void terminalSemanticsUnchanged() {
        Set<CheckpointStatus> terminal = CheckpointStatus.terminalStatuses();
        assertTrue(terminal.contains(CheckpointStatus.COMPLETED));
        assertTrue(terminal.contains(CheckpointStatus.FAILED));
        assertTrue(terminal.contains(CheckpointStatus.RESUMING));
        assertTrue(CheckpointStatus.COMPLETED.isTerminal());
        assertTrue(!CheckpointStatus.IN_PROGRESS.isTerminal());
    }
}
