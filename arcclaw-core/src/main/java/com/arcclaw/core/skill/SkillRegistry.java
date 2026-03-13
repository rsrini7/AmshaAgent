package com.arcclaw.core.skill;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory registry for all currently active agent skills.
 * <p>
 * Uses an {@link AtomicReference} pointing to an immutable list of
 * {@link CompiledSkillEntry} to provide thread-safe, lock-free reads
 * during the high-throughput intent classification and routing loops.
 */
@ApplicationScoped
public class SkillRegistry {

    private final AtomicReference<List<CompiledSkillEntry>> registryRef = new AtomicReference<>(List.of());

    /**
     * Atomically replaces the current registry state with a newly compiled list.
     * 
     * @param newEntries An immutable list of compiled skills.
     */
    public void reload(List<CompiledSkillEntry> newEntries) {
        Map<String, CompiledSkillEntry> map = new LinkedHashMap<>();
        for (CompiledSkillEntry entry : newEntries) {
            map.put(entry.definition().name(), entry);
        }
        registryRef.set(List.copyOf(map.values()));
    }

    /**
     * @return The immutable list of all currently active skills.
     */
    public List<CompiledSkillEntry> getAll() {
        return registryRef.get();
    }

    /**
     * @return The number of currently loaded skills.
     */
    public int size() {
        return registryRef.get().size();
    }

    /**
     * Scans the active registry to find the first skill whose pre-compiled
     * regex paths trigger a match for the given user input.
     * 
     * @param input the raw user message text
     * @return An Optional containing the first matching skill entry, or empty if none match.
     */
    public Optional<CompiledSkillEntry> findMatch(String input) {
        if (input == null || input.isBlank()) return Optional.empty();
        
        for (CompiledSkillEntry entry : registryRef.get()) {
            if (entry.matches(input)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }
}
