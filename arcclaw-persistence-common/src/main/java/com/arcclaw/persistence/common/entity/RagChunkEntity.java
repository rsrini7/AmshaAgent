package com.arcclaw.persistence.common.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Persists the raw text content for a vector embedding chunk.
 */
@Entity
@Table(name = "rag_chunk")
public class RagChunkEntity {

    @Id
    @Column(name = "chunk_id")
    public String chunkId;

    @Column(columnDefinition = "TEXT", nullable = false)
    public String text;

    @Column(nullable = false)
    public String source;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
