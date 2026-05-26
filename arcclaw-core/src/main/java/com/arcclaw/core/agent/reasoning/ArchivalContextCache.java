package com.arcclaw.core.agent.reasoning;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.arcclaw.core.scope.WorkspaceScoped;

/**
 * Caches pre-fetched archival context for the duration of a workspace session.
 * <p>
 * Populated by {@code ArchivalMemorySearchTool} after a successful search.
 * Consumed by {@link ChatHistoryAssembler} for O(1) prompt injection — no I/O.
 * <p>
 * This enforces the tool-first, never unconditional policy: the assembler
 * only injects archival context that was explicitly retrieved via tool call.
 * <p>
 * Thread safety: uses a single {@link AtomicReference} to hold both the loaded
 * flag and text as an immutable snapshot — eliminates split-brain state from
 * two separate volatile fields.
 *
 * @see ChatHistoryAssembler
 * @see com.arcclaw.core.tools.memory.ArchivalMemorySearchTool
 */
@WorkspaceScoped
public class ArchivalContextCache {

    /** Immutable snapshot of cache state — either empty or populated. */
    private record CacheState(boolean loaded, String text) {
        static CacheState empty() { return new CacheState(false, null); }
        static CacheState of(String text) { return new CacheState(true, text); }
    }

    private final AtomicReference<CacheState> state = new AtomicReference<>(CacheState.empty());

    /**
     * Called by ArchivalMemorySearchTool after successful search to store
     * formatted archival results for the current session.
     */
    public void put(String formattedText) {
        state.set(CacheState.of(formattedText));
    }

    /**
     * Called by ChatHistoryAssembler — O(1) lookup, no I/O.
     * Returns the cached formatted text if a search was performed this session.
     */
    public Optional<String> getText() {
        CacheState s = state.get();
        return s.loaded() ? Optional.ofNullable(s.text()) : Optional.empty();
    }

    /**
     * Invalidate cached context (e.g., on session reset).
     */
    public void invalidate() {
        state.set(CacheState.empty());
    }
}
