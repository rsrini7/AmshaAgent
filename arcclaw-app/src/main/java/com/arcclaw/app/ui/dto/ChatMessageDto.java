package com.arcclaw.app.ui.dto;

import dev.langchain4j.data.message.*;

public record ChatMessageDto(String role, String text) {

    public static ChatMessageDto from(ChatMessage msg) {
        return switch (msg) {
            case UserMessage u  -> new ChatMessageDto("user",      u.singleText());
            case AiMessage a    -> new ChatMessageDto("assistant", a.text());
            case SystemMessage s-> new ChatMessageDto("system",    s.text());
            default             -> new ChatMessageDto("system",    msg.toString());
        };
    }
}
