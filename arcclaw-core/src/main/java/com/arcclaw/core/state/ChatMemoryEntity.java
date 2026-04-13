package com.arcclaw.core.state;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA entity for persistent chat memory storage.
 * Stores conversation history as a JSON string.
 */
@Entity
@Table(name = "chat_memory")
public class ChatMemoryEntity {

    @Id
    @Column(name = "memory_id")
    private String memoryId;

    @Column(name = "messages_json", columnDefinition = "TEXT", nullable = false)
    private String messagesJson;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    /**
     * Default constructor required by JPA.
     */
    public ChatMemoryEntity() {
    }

    /**
     * Full constructor.
     * 
     * @param memoryId     Unique ID for the chat memory (usually sessionId)
     * @param messagesJson Serialized List of ChatMessage
     * @param metadataJson Serialized message metadata (e.g. timestamps)
     */
    public ChatMemoryEntity(String memoryId, String messagesJson, String metadataJson) {
        this.memoryId = memoryId;
        this.messagesJson = messagesJson;
        this.metadataJson = metadataJson;
    }

    // Getters and Setters

    public String getMemoryId() {
        return memoryId;
    }

    public void setMemoryId(String memoryId) {
        this.memoryId = memoryId;
    }

    public String getMessagesJson() {
        return messagesJson;
    }

    public void setMessagesJson(String messagesJson) {
        this.messagesJson = messagesJson;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson;
    }
}
