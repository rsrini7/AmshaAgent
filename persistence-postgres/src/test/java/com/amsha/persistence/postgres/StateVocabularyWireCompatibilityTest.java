package com.amsha.persistence.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CronStatus;
import com.amsha.persistence.api.ToolDecision;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * Wire compatibility for the state vocabulary after its move from
 * {@code com.amsha.core.state} to {@code com.amsha.persistence.api}: persisted JSON
 * documents and SQLite storage values must be byte-identical to the legacy format.
 */
public class StateVocabularyWireCompatibilityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void checkpointStatusSerializesToLegacyNames() throws Exception {
        for (CheckpointStatus status : CheckpointStatus.values()) {
            String json = objectMapper.writeValueAsString(status);
            assertEquals(
                    "\"" + status.name() + "\"",
                    json,
                    "persisted JSON for CheckpointStatus must stay the bare enum name");
            assertEquals(status, objectMapper.readValue(json, CheckpointStatus.class));
        }
    }

    @Test
    public void cronStatusStorageValuesAreStable() throws Exception {
        for (CronStatus status : CronStatus.values()) {
            assertEquals(
                    status.name(), status.toStorageValue(), "SQLite storage value must equal the legacy enum name");
            assertEquals(status, CronStatus.fromStorageValue(status.toStorageValue()));
            String json = objectMapper.writeValueAsString(status);
            assertEquals("\"" + status.name() + "\"", json);
        }
        assertEquals(CronStatus.ACTIVE, CronStatus.fromStorageValue(null));
        assertEquals(CronStatus.ACTIVE, CronStatus.fromStorageValue("  "));
    }

    @Test
    public void toolDecisionSerializesToLegacyNames() throws Exception {
        for (ToolDecision decision : ToolDecision.values()) {
            String json = objectMapper.writeValueAsString(decision);
            assertEquals(
                    "\"" + decision.name() + "\"",
                    json,
                    "tool-decision JSON embedded in checkpoints must stay the bare enum name");
            assertEquals(decision, objectMapper.readValue(json, ToolDecision.class));
        }
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
