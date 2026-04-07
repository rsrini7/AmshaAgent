package com.arcclaw.app.ui.dto;

import dev.langchain4j.data.message.*;

public record ChatMessageDto(String role, String text, Long actionId) {

    public static ChatMessageDto from(ChatMessage msg) {
        return switch (msg) {
            case UserMessage u  -> new ChatMessageDto("user",      u.singleText(), null);
            case AiMessage a    -> new ChatMessageDto("assistant", a.text(), null);
            case SystemMessage s-> new ChatMessageDto("system",    s.text(), null);
            default             -> new ChatMessageDto("system",    msg.toString(), null);
        };
    }
}
