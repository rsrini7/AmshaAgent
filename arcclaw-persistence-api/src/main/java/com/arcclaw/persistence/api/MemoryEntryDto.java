package com.arcclaw.persistence.api;

import java.util.Map;

/**
 * Transfer object for indexing memory entries.
 */
public record MemoryEntryDto(String id, String text, Map<String, String> metadata) {}
