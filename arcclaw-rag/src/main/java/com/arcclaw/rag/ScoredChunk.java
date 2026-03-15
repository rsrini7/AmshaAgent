package com.arcclaw.rag;

public record ScoredChunk(String chunkId, String text, double score) {}
