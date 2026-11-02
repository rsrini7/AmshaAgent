package com.amsha.persistence.api;

/**
 * Shared contract constants for tool-attempt settlement persistence and reset validation.
 *
 * <p>Under ADR-Tool-Attempt-Settlement-Reset-Only-V1-Baseline, these identifiers define
 * the single source of truth for the schema contract name, version, manifest file name,
 * and database marker table.
 */
public final class SchemaContract {

    public static final String CONTRACT_NAME = "tool-attempt-settlement";
    public static final int CONTRACT_VERSION = 1;
    public static final String SCHEMA_CONTRACT_FILE = "schema-contract.json";
    public static final String SCHEMA_CONTRACT_MARKER_TABLE = "schema_contract_marker";

    private SchemaContract() {}
}
