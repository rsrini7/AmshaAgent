package com.amsha.persistence.api;

/**
 * Result of a memory ingestion operation.
 *
 * @param id the unique identifier of the ingested chunk (or fallback ID)
 * @param isGlobalFallback true if the document was only stored in session-local memory
 *                         because the global knowledge base was unavailable.
 */
public record IngestResult(String id, boolean isGlobalFallback) {}
