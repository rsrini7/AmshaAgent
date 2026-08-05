package com.amsha.persistence.api;

/** Durable or rebuildable vector namespaces that require descriptor validation. */
public enum VectorIndexNamespace {
    RAG_GLOBAL("rag-global"),
    ARCHIVAL("archival");

    private final String persistentId;

    VectorIndexNamespace(String persistentId) {
        this.persistentId = persistentId;
    }

    public String persistentId() {
        return persistentId;
    }
}
