package com.arcclaw.persistence.sqlite.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Stores text content for standalone RAG chunks.
 */
@Entity
@Table(name = "rag_chunk")
public class RagChunkEntity {

    @Id
    @Column(name = "chunk_id", nullable = false)
    public String chunkId;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String text;

    @Column
    public String source;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}

