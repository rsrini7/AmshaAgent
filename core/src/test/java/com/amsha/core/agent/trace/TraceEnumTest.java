package com.amsha.core.agent.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Guards the {@code snake_case} wire contract of {@link TraceStage} and {@link StationCode}.
 *
 * <p>The wire value is declared explicitly per constant, so a rename of a Java constant must
 * never silently change the JSON the UI depends on.
 */
class TraceEnumTest {

    private static final List<String> REQUIRED_STAGES = List.of(
            "turn_start",
            "preflight",
            "route",
            "memory_decision",
            "guard_check",
            "hitl_requested",
            "hitl_resolved",
            "llm_reasoning",
            "tool_start",
            "tool_end",
            "tool_error",
            "quality_check",
            "retry",
            "hitl_timeout",
            "loop_stuck",
            "consolidation",
            "turn_end");

    @Test
    void everyRequiredTraceStageExists() {
        List<String> wireValues =
                Arrays.stream(TraceStage.values()).map(TraceStage::wireValue).toList();

        assertEquals(REQUIRED_STAGES.size(), TraceStage.values().length);
        assertTrue(
                wireValues.containsAll(REQUIRED_STAGES),
                "missing stages: "
                        + REQUIRED_STAGES.stream()
                                .filter(s -> !wireValues.contains(s))
                                .toList());
    }

    @Test
    void everyStageWireValueIsLowercaseSnakeCase() {
        for (TraceStage stage : TraceStage.values()) {
            assertEquals(stage.wireValue(), stage.wireValue().toLowerCase(), stage.name());
            assertTrue(stage.wireValue().matches("[a-z0-9]+(_[a-z0-9]+)*"), stage.wireValue());
            assertEquals(stage, TraceStage.fromWireValue(stage.wireValue()));
        }
    }

    @Test
    void stationCodesCoverExactlyTenOrderedStations() {
        assertEquals(10, StationCode.values().length);

        List<String> wireValues =
                Arrays.stream(StationCode.values()).map(StationCode::wireValue).toList();
        assertEquals(
                List.of(
                        "station_01_inbound",
                        "station_02_preflight",
                        "station_03_routing",
                        "station_04_memory_decision",
                        "station_05_tool_guard",
                        "station_06_reasoning",
                        "station_07_tool_execution",
                        "station_08_quality",
                        "station_09_consolidation",
                        "station_10_outbound"),
                wireValues);
    }

    @Test
    void everyStationWireValueIsExplicitLowercaseSnakeCase() {
        for (StationCode station : StationCode.values()) {
            assertEquals(station.wireValue(), station.wireValue().toLowerCase(), station.name());
            assertTrue(station.wireValue().matches("station_[0-9]{2}_[a-z0-9]+(_[a-z0-9]+)*"), station.wireValue());
            assertEquals(station, StationCode.fromWireValue(station.wireValue()));
        }
    }

    @Test
    void stationConstantsUseUpperSnakeCaseNames() {
        for (StationCode station : StationCode.values()) {
            assertTrue(station.name().matches("STATION_[0-9]{2}_[A-Z0-9]+(_[A-Z0-9]+)*"), station.name());
        }
    }

    @Test
    void unknownWireValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> TraceStage.fromWireValue("not_a_stage"));
        assertThrows(IllegalArgumentException.class, () -> StationCode.fromWireValue("station_99_nope"));
    }
}
