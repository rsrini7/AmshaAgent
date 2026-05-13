package com.arcclaw.core.domain.model;

public interface ChatMemory {

    String getMemoryId();
    void setMemoryId(String memoryId);

    String getMessagesJson();
    void setMessagesJson(String messagesJson);

    String getMetadataJson();
    void setMetadataJson(String metadataJson);
}
