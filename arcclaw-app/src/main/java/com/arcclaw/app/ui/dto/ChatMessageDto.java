package com.arcclaw.app.ui.dto;

import dev.langchain4j.data.message.*;

public record ChatMessageDto(String role, String text, Long actionId, Long timestamp) {

    public static ChatMessageDto from(ChatMessage msg) {
        return from(msg, null);
    }

    public static ChatMessageDto from(ChatMessage msg, Long timestamp) {
        return switch (msg) {
            case UserMessage u  -> new ChatMessageDto("user",      u.singleText(), null, timestamp);
            case AiMessage a    -> new ChatMessageDto("assistant", a.text(),       null, timestamp);
            case SystemMessage s-> new ChatMessageDto("system",    s.text(),       null, timestamp);
            default             -> new ChatMessageDto("system",    msg.toString(), null, timestamp);
        };
    }
}
