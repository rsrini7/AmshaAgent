package com.arcclaw.core.domain.model;

import com.arcclaw.persistence.api.ChatMemoryDto;

public final class ChatMemoryRecord implements ChatMemory {

    private String memoryId;
    private String messagesJson;
    private String metadataJson;

    public ChatMemoryRecord() {}

    public static ChatMemoryRecord fromDto(ChatMemoryDto dto) {
        ChatMemoryRecord r = new ChatMemoryRecord();
        r.memoryId = dto.memoryId();
        r.messagesJson = dto.messagesJson();
        r.metadataJson = dto.metadataJson();
        return r;
    }

    @Override public String getMemoryId() { return memoryId; }
    @Override public void setMemoryId(String memoryId) { this.memoryId = memoryId; }
    @Override public String getMessagesJson() { return messagesJson; }
    @Override public void setMessagesJson(String messagesJson) { this.messagesJson = messagesJson; }
    @Override public String getMetadataJson() { return metadataJson; }
    @Override public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
}
