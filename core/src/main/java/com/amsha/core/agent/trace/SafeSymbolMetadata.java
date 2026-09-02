package com.amsha.core.agent.trace;

/**
 * Safe symbolication metadata for operator X-Ray drawer.
 *
 * <p>Invariant: Prompts, completions, secrets, authorization headers, tool parameters,
 * audit keys, and raw Java source are NEVER exposed in this model.
 */
public record SafeSymbolMetadata(
        String symbol, String station, String label, String auditHash, String subsystem, String safetyInvariant) {

    public static SafeSymbolMetadata publicMetadata(String symbol, String station, String label, String auditHash) {
        return new SafeSymbolMetadata(symbol, station, label, auditHash, null, null);
    }

    public static SafeSymbolMetadata internalMetadata(
            String symbol, String station, String label, String auditHash, String subsystem, String safetyInvariant) {
        return new SafeSymbolMetadata(symbol, station, label, auditHash, subsystem, safetyInvariant);
    }
}
