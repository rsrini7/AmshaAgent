package com.amsha.core.state;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import com.amsha.core.domain.model.AgentCheckpointRecord;
import com.fasterxml.jackson.annotation.JsonIgnore;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class AgentCheckpointTest {

    @Test
    void testFieldPrivacyAndJsonIgnore() throws NoSuchFieldException {
        Class<AgentCheckpointRecord> clazz = AgentCheckpointRecord.class;

        // 1. Verify fields are private
        Field stateJson = clazz.getDeclaredField("stateJson");
        Assertions.assertTrue(Modifier.isPrivate(stateJson.getModifiers()), "stateJson should be private");

        Field status = clazz.getDeclaredField("status");
        Assertions.assertTrue(Modifier.isPrivate(status.getModifiers()), "status should be private");

        // 2. Verify sensitive data has @JsonIgnore
        Field pendingToolParams = clazz.getDeclaredField("pendingToolParametersJson");
        Assertions.assertTrue(
                pendingToolParams.isAnnotationPresent(JsonIgnore.class),
                "pendingToolParametersJson must have @JsonIgnore for PII privacy (Phase 41)");
    }
}
