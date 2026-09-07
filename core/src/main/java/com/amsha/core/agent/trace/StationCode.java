package com.amsha.core.agent.trace;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The ten governed harness stations rendered by the cockpit conveyor.
 *
 * <p>Each constant serializes to an explicit lowercase {@code snake_case} wire value (for
 * example {@code STATION_05_TOOL_GUARD} serializes as {@code station_05_tool_guard}). The wire
 * value is declared per constant and never derived from {@link #name()}. See
 * {@code .scratch/amsha-visual-cockpit/spec.md} §2.4.
 */
public enum StationCode {
    STATION_01_INBOUND("station_01_inbound"),
    STATION_02_PREFLIGHT("station_02_preflight"),
    STATION_03_ROUTING("station_03_routing"),
    STATION_04_MEMORY_DECISION("station_04_memory_decision"),
    STATION_05_TOOL_GUARD("station_05_tool_guard"),
    STATION_06_REASONING("station_06_reasoning"),
    STATION_07_TOOL_EXECUTION("station_07_tool_execution"),
    STATION_08_QUALITY("station_08_quality"),
    STATION_09_CONSOLIDATION("station_09_consolidation"),
    STATION_10_OUTBOUND("station_10_outbound");

    private final String wireValue;

    StationCode(String wireValue) {
        this.wireValue = wireValue;
    }

    /** Returns the explicit lowercase {@code snake_case} wire value of this station. */
    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    /**
     * Resolves a station from its wire value.
     *
     * @throws IllegalArgumentException if the value is not part of this contract version
     */
    @JsonCreator
    public static StationCode fromWireValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (StationCode station : values()) {
            if (station.wireValue.equals(value)) {
                return station;
            }
        }
        throw new IllegalArgumentException("Unknown StationCode wire value: " + value);
    }
}
