package com.amsha.core.agent.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Issue 06 AC3, AC4: Symbolication contract & safe-metadata authorization boundary.
 *
 * <p>Validates that:
 * <ul>
 *   <li>Non-internal callers receive only public safe metadata (subsystem and safetyInvariant null).</li>
 *   <li>Internal callers receive extended safe metadata.</li>
 *   <li>Raw Java source, parameters, secrets, and keys are NEVER returned in either response.</li>
 *   <li>The core service never trusts a client-provided privilege flag: caller must pass the resolved flag.</li>
 *   <li>Unknown or non-allowlisted symbols return empty.</li>
 * </ul>
 */
class SymbolicationAuthzTest {

    private TraceSymbolicationService service;

    @BeforeEach
    void setUp() {
        service = new TraceSymbolicationService();
    }

    @Test
    void nonInternalCallerReceivesOnlyPublicSafeMetadata() {
        Optional<SafeSymbolMetadata> meta =
                service.symbolicate("com.amsha.core.agent.loop.ToolInvocationGuard:120", false);

        assertTrue(meta.isPresent(), "Allowlisted symbol must be resolved");
        SafeSymbolMetadata data = meta.get();
        assertEquals("station_05_tool_guard", data.station());
        assertEquals("Tool Guard Check", data.label());
        assertNotNull(data.auditHash());
        assertEquals(64, data.auditHash().length(), "Audit hash must be 64-character SHA-256 hex");

        // Extended architectural details MUST be withheld from non-internal callers
        assertNull(data.subsystem(), "Subsystem must be withheld from non-internal callers");
        assertNull(data.safetyInvariant(), "Safety invariant must be withheld from non-internal callers");
    }

    @Test
    void internalCallerReceivesExtendedSafeMetadata() {
        Optional<SafeSymbolMetadata> meta =
                service.symbolicate("com.amsha.core.agent.loop.ToolInvocationGuard:120", true);

        assertTrue(meta.isPresent());
        SafeSymbolMetadata data = meta.get();
        assertEquals("station_05_tool_guard", data.station());
        assertEquals("Tool Guard Check", data.label());
        assertNotNull(data.auditHash());
        assertEquals(64, data.auditHash().length(), "Audit hash must be 64-character SHA-256 hex");

        // Extended architectural details provided
        assertEquals("Governance", data.subsystem());
        assertNotNull(data.safetyInvariant());
        assertTrue(data.safetyInvariant().contains("§4.12"));
    }

    @Test
    void rawSourceAndParametersAreNeverExposed() {
        Optional<SafeSymbolMetadata> meta =
                service.symbolicate("com.amsha.core.agent.hitl.CheckpointApprovalService:45", true);

        assertTrue(meta.isPresent());
        SafeSymbolMetadata data = meta.get();

        // Ensure toString() or field values never leak raw source indicators
        String stringified = data.toString();
        assertFalse(stringified.contains("class CheckpointApprovalService {"));
        assertFalse(stringified.contains("void approve("));
        assertFalse(stringified.contains("secret"));
        assertFalse(stringified.contains("password"));
    }

    @Test
    void unknownOrNonAllowlistedSymbolsReturnEmpty() {
        Optional<SafeSymbolMetadata> meta = service.symbolicate("com.amsha.arbitrary.UnknownClass:99", true);

        assertFalse(meta.isPresent(), "Non-allowlisted class must not be symbolicated");
    }

    @Test
    void blankOrNullSymbolReturnsEmpty() {
        assertFalse(service.symbolicate(null, true).isPresent());
        assertFalse(service.symbolicate("", true).isPresent());
        assertFalse(service.symbolicate("   ", true).isPresent());
    }

    @Test
    void compactionEventBridgeAndChatHistoryAssemblerAreAllowlisted() {
        Optional<SafeSymbolMetadata> compaction = service.symbolicate("CompactionEventBridge", true);
        assertTrue(compaction.isPresent(), "CompactionEventBridge must be in allowlist");
        assertEquals("station_09_consolidation", compaction.get().station());
        assertEquals("Memory", compaction.get().subsystem());

        Optional<SafeSymbolMetadata> chatHistory = service.symbolicate("ChatHistoryAssembler", true);
        assertTrue(chatHistory.isPresent(), "ChatHistoryAssembler must be in allowlist");
        assertEquals("station_04_memory_decision", chatHistory.get().station());
        assertEquals("Memory", chatHistory.get().subsystem());
    }
}
