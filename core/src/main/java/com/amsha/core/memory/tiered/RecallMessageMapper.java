package com.amsha.core.memory.tiered;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import com.amsha.persistence.api.RecallMessageDto;

/**
 * Maps LangChain4j {@link ChatMessage} objects to {@link RecallMessageDto} records.
 */
public final class RecallMessageMapper {

    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile(
            "(?i)\\b(function|def |class |package |import |SELECT |FROM |WHERE |INSERT |UPDATE |DELETE )");
    private static final Pattern ERROR_PATTERN =
            Pattern.compile("(?i)\\b(error|exception|failed|traceback|stack\\s?trace)\\b");

    private RecallMessageMapper() {}

    public static List<RecallMessageDto> map(String sessionId, String userId, List<ChatMessage> messages) {
        return map(sessionId, userId, messages, 0);
    }

    public static List<RecallMessageDto> map(
            String sessionId, String userId, List<ChatMessage> messages, int startTurn) {
        List<RecallMessageDto> dtos = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            String content = extractContent(msg);
            String role = extractRole(msg);
            String type = extractType(msg);

            RecallMessageDto dto = new RecallMessageDto(
                    UUID.randomUUID().toString(),
                    sessionId,
                    userId,
                    startTurn + i,
                    sha256(content),
                    role,
                    type,
                    content,
                    null,
                    null,
                    CODE_BLOCK_PATTERN.matcher(content).find(),
                    ERROR_PATTERN.matcher(content).find(),
                    Instant.now(),
                    null);
            dtos.add(dto);
        }
        return dtos;
    }

    public static RecallMessageDto compactionSummary(String sessionId, String userId, int turnNumber, String summary) {
        return new RecallMessageDto(
                UUID.randomUUID().toString(),
                sessionId,
                userId,
                turnNumber,
                sha256(summary),
                "system",
                "COMPACTION_SUMMARY",
                summary,
                null,
                null,
                false,
                false,
                Instant.now(),
                null);
    }

    private static String extractContent(ChatMessage msg) {
        if (msg instanceof AiMessage ai) {
            return ai.text() != null ? ai.text() : "";
        }
        if (msg instanceof SystemMessage sys) {
            return sys.text() != null ? sys.text() : "";
        }
        return msg.toString();
    }

    private static String extractRole(ChatMessage msg) {
        if (msg instanceof UserMessage) return "user";
        if (msg instanceof AiMessage) return "agent";
        if (msg instanceof SystemMessage) return "system";
        return "unknown";
    }

    private static String extractType(ChatMessage msg) {
        if (msg instanceof UserMessage) return "USER_MESSAGE";
        if (msg instanceof AiMessage) return "AGENT_MESSAGE";
        if (msg instanceof SystemMessage) return "SYSTEM_NOTE";
        return "UNKNOWN";
    }

    private static String sha256(String content) {
        if (content == null || content.isEmpty()) {
            return "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
