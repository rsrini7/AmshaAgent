package com.amsha.selfevolve.api;

/**
 * Typed record types for extracted memories from agent interaction traces.
 * Each type represents a distinct pattern identified by rule-based extraction (Phase 9).
 */
public enum MemoryRecordType {
    USER_PREFERENCE,
    USER_PROFILE,
    ACTIVE_TASK,
    RESOLUTION_PATTERN,
    FAILURE_PATTERN,
    TOOL_HINT,
    PROJECT_CONTEXT,
    DIRECTIVE,
    SUMMARY,
    FACT,
    ERROR_CONTEXT,
    TOOL_MISUSE_PATTERN,
    WORKAROUND
}
