package com.amsha.core.agent.trace;

import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * Contract and safe-metadata resolver for the Visual Cockpit operator X-Ray.
 *
 * <p>Performs NO authorization: accepts {@code isInternal} boolean supplied by caller
 * (the application/resource layer) and widens metadata only when true.
 * The core service never trusts a client-provided privilege flag.
 */
@ApplicationScoped
public class TraceSymbolicationService {

    record SymbolEntry(String symbol, String station, String label, String subsystem, String safetyInvariant) {}

    private final Map<String, SymbolEntry> allowlist;

    public TraceSymbolicationService() {
        Map<String, SymbolEntry> map = new HashMap<>();
        register(
                map,
                "ToolInvocationGuard",
                "station_05_tool_guard",
                "Tool Guard Check",
                "Governance",
                "§4.12 / §7.7 Tool Guard Invariant");
        register(
                map,
                "CheckpointApprovalService",
                "station_05_tool_guard",
                "HITL Approval Checkpoint",
                "Governance",
                "§5.10 Approval Invariant");
        register(
                map,
                "EngineStepHandler",
                "station_06_reasoning",
                "LLM Reasoning Iteration",
                "Cognition",
                "§1.4 Reasoning Invariant");
        register(
                map,
                "SequentialToolExecutor",
                "station_07_tool_execution",
                "Sequential Tool Executor",
                "Execution",
                "§5.9 Transaction-Free Invariant");
        register(
                map,
                "FinishActionHandler",
                "station_08_quality",
                "Answer Quality Final Check",
                "Quality",
                "Quality Guard Invariant");
        register(
                map,
                "SessionReaper",
                "station_05_tool_guard",
                "Stale Approval Auto-Denial",
                "Lifecycle",
                "Stale Approval Denial Invariant");
        register(
                map,
                "AgentLoop",
                "station_01_inbound",
                "Agent Inbound / Outbound Coordinator",
                "Core",
                "Loop Invariant");
        register(
                map,
                "ChatHistoryAssembler",
                "station_04_memory_decision",
                "Tiered Memory Assembly & Prompt Budget",
                "Memory",
                "Tiered Memory Budget Invariant");
        register(
                map,
                "CompactionEventBridge",
                "station_09_consolidation",
                "History Compaction & Consolidation",
                "Memory",
                "Memory Consolidation Invariant");
        this.allowlist = Map.copyOf(map);
    }

    private static void register(
            Map<String, SymbolEntry> map,
            String name,
            String station,
            String label,
            String subsystem,
            String invariant) {
        map.put(name, new SymbolEntry(name, station, label, subsystem, invariant));
    }

    /**
     * Resolves safe symbolication metadata.
     *
     * <p>The {@code isInternal} flag determines whether extended architectural metadata
     * (subsystem, safety invariant) is included. This method never performs authorization itself
     * and never inspects headers: caller (resource layer) must enforce authorization.
     *
     * <p>The returned audit hash is computed deterministically on the server from the allowlisted
     * entry's immutable provenance contract, preventing callers from forging audit proofs.
     */
    public Optional<SafeSymbolMetadata> symbolicate(String symbolKey, boolean isInternal) {
        if (symbolKey == null || symbolKey.isBlank()) {
            return Optional.empty();
        }

        String simpleKey = extractSimpleName(symbolKey);
        SymbolEntry entry = allowlist.get(simpleKey);
        if (entry == null) {
            return Optional.empty();
        }

        String verifiedHash = computeAuditHash(entry);

        if (isInternal) {
            return Optional.of(SafeSymbolMetadata.internalMetadata(
                    symbolKey,
                    entry.station(),
                    entry.label(),
                    verifiedHash,
                    entry.subsystem(),
                    entry.safetyInvariant()));
        }
        return Optional.of(SafeSymbolMetadata.publicMetadata(symbolKey, entry.station(), entry.label(), verifiedHash));
    }

    private static String computeAuditHash(SymbolEntry entry) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String payload = "AMSHA_AUDIT_PROVENANCE_V1:" + entry.station() + ":" + entry.symbol() + ":"
                    + entry.safetyInvariant();
            byte[] digest = md.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            return "unspecified";
        }
    }

    private static String extractSimpleName(String symbol) {
        String clean = symbol.split(":")[0].trim();
        int lastDot = clean.lastIndexOf('.');
        return lastDot >= 0 ? clean.substring(lastDot + 1) : clean;
    }
}
