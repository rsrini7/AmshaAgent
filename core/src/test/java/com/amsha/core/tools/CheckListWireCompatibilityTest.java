package com.amsha.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

public class CheckListWireCompatibilityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void testSerializationMatchesLegacyWireFormat() throws Exception {
        CheckListItem item1 = new CheckListItem("Step 1: Planning", CheckListStatus.completed, "Planning completed");
        CheckListItem item2 = new CheckListItem("Step 2: Execution", CheckListStatus.in_progress, "Executing");
        CheckListItem item3 = new CheckListItem("Step 3: Verification", CheckListStatus.pending, null);

        CheckList list = new CheckList(List.of(item1, item2, item3));
        CheckListEvent event = new CheckListEvent("session-123", list);

        String json = objectMapper.writeValueAsString(event);
        assertTrue(json.contains("\"status\":\"completed\""));
        assertTrue(json.contains("\"status\":\"in_progress\""));
        assertTrue(json.contains("\"status\":\"pending\""));
        assertTrue(json.contains("\"content\":\"Step 1: Planning\""));
        assertTrue(json.contains("\"sessionId\":\"session-123\""));

        CheckListEvent deserialized = objectMapper.readValue(json, CheckListEvent.class);
        assertEquals("session-123", deserialized.sessionId());
        assertEquals(3, deserialized.checkList().checkList().size());
        assertEquals(
                CheckListStatus.completed,
                deserialized.checkList().checkList().get(0).status());
        assertEquals(
                CheckListStatus.in_progress,
                deserialized.checkList().checkList().get(1).status());
        assertEquals(
                CheckListStatus.pending,
                deserialized.checkList().checkList().get(2).status());
    }
}
