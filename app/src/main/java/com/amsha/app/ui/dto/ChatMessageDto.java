package com.amsha.app.ui.dto;

import dev.langchain4j.data.message.*;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatMessageDto(
        @NotBlank String role, @NotBlank @Size(max = 100000) String text, Long actionId, Long timestamp) {

    public static ChatMessageDto from(ChatMessage msg) {
        return from(msg, null);
    }

    public static ChatMessageDto from(ChatMessage msg, Long timestamp) {
        return switch (msg) {
            case UserMessage u -> new ChatMessageDto("user", u.singleText(), null, timestamp);
            case AiMessage a -> new ChatMessageDto("assistant", a.text(), null, timestamp);
            case SystemMessage s -> new ChatMessageDto("system", s.text(), null, timestamp);
            default -> new ChatMessageDto("system", msg.toString(), null, timestamp);
        };
    }
}
