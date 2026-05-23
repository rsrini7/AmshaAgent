-- pgvector extension for vector similarity search
CREATE EXTENSION IF NOT EXISTS vector;

-- Embedding storage table (links to rag_chunk via chunk_id)
CREATE TABLE IF NOT EXISTS rag_chunk_embedding (
    chunk_id    TEXT PRIMARY KEY REFERENCES rag_chunk(chunk_id) ON DELETE CASCADE,
    embedding   vector(1536)
);

-- HNSW index for fast approximate nearest neighbor search
CREATE INDEX IF NOT EXISTS idx_rag_chunk_embedding_vec
    ON rag_chunk_embedding
    USING hnsw (embedding vector_cosine_ops);
