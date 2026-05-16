package com.arcclaw.core.domain.model;

public interface ChatMemory {

    String getMemoryId();
    void setMemoryId(String memoryId);

    String getMessagesJson();
    void setMessagesJson(String messagesJson);

    String getMetadataJson();
    void setMetadataJson(String metadataJson);

    // ── Wither methods ────────────────────────────────────────────────────

    default ChatMemory withMemoryId(String m) { setMemoryId(m); return this; }
    default ChatMemory withMessagesJson(String j) { setMessagesJson(j); return this; }
    default ChatMemory withMetadataJson(String j) { setMetadataJson(j); return this; }
}
