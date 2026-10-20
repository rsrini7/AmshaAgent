package com.amsha.core.classifier.evaluation;

import java.util.Locale;

import com.amsha.security.api.ToolOperation;

/** Shared parsing and bounded legacy mapping for tool-risk evidence requests. */
public final class DecisionEvidenceToolOperationMapper {

    private DecisionEvidenceToolOperationMapper() {}

    /** Parses an explicit operation without exposing raw corpus values in errors. */
    public static ToolOperation parseExplicit(String operation) {
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("missing required operation");
        }
        try {
            return ToolOperation.valueOf(operation.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException _) {
            throw new IllegalArgumentException("invalid required operation");
        }
    }

    /** Maps only the legacy tool names supported by shipped pre-operation corpora. */
    public static ToolOperation legacyForToolName(String toolName) {
        if (toolName == null) {
            throw new IllegalArgumentException("missing legacy tool name");
        }
        return switch (toolName.trim().toLowerCase(Locale.ROOT)) {
            case "read_file" -> ToolOperation.READ_FS;
            case "write_file" -> ToolOperation.WRITE_FS;
            case "execute_shell", "sandbox_exec", "host_exec" -> ToolOperation.EXECUTE_SHELL;
            case "network_outbound" -> ToolOperation.NETWORK_OUTBOUND;
            default -> throw new IllegalArgumentException("unmapped legacy tool name");
        };
    }

    /** Resolves the explicit input value, rejecting wrong JSON types instead of falling back. */
    public static ToolOperation fromInputValue(Object rawOperation, String legacyToolName, boolean operationRequired) {
        if (rawOperation instanceof String operation) {
            return parseExplicit(operation);
        }
        if (rawOperation != null) {
            throw new IllegalArgumentException("invalid required operation");
        }
        if (operationRequired) {
            throw new IllegalArgumentException("missing required operation");
        }
        return legacyForToolName(legacyToolName);
    }
}
