package com.amsha.persistence.api;

/**
 * Transfer object for chat memory.
 * §1.2: Core-persistence boundary.
 */
public record ChatMemoryDto(String memoryId, String messagesJson, String metadataJson) {}
