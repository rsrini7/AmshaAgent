package com.amsha.persistence.api;

/**
 * Data class representing the capabilities of a persistence backend.
 * Used to determine what features are available for a given storage implementation.
 */
public record BackendCapabilities(
        boolean supportsFullTextSearch,
        boolean supportsVectorSearch,
        boolean supportsAdvisoryLocks,
        boolean supportsNestedTransactions) {}
