package com.amsha.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Guards the persisted-chat-memory JSON contract: whatever
 * {@link ChatMessageSerializer#messagesToJson} writes must be readable by
 * {@link ChatMessageDeserializer#messagesFromJson} across every message type
 * Amsha stores. A LangChain4j upgrade that changes this format breaks old
 * sessions at runtime (InvalidTypeIdException on read) — this test fails at
 * build time instead.
 */
@QuarkusTest
class ChatMessageJsonRoundTripTest {

    @Test
    void userSystemAiMessagesRoundTrip() {
        List<ChatMessage> messages =
                List.of(SystemMessage.from("system prompt"), UserMessage.from("Hi"), AiMessage.from("Hello!"));
        String json = ChatMessageSerializer.messagesToJson(messages);
        assertTrue(json.contains("\"type\":\"USER\""), "message-level type id must survive serialization");

        List<ChatMessage> restored = ChatMessageDeserializer.messagesFromJson(json);
        assertEquals(messages.size(), restored.size());
        assertInstanceOf(SystemMessage.class, restored.get(0));
        assertInstanceOf(UserMessage.class, restored.get(1));
        assertInstanceOf(AiMessage.class, restored.get(2));
        assertEquals("Hi", ((UserMessage) restored.get(1)).singleText());
    }

    @Test
    void toolMessagesRoundTrip() {
        List<ChatMessage> messages = List.of(
                ToolExecutionResultMessage.from("tool-1", "read_file", "{\"ok\":true}"), AiMessage.from("done"));
        String json = ChatMessageSerializer.messagesToJson(messages);
        List<ChatMessage> restored = ChatMessageDeserializer.messagesFromJson(json);

        assertEquals(messages.size(), restored.size());
        ToolExecutionResultMessage toolResult = assertInstanceOf(ToolExecutionResultMessage.class, restored.get(0));
        assertEquals("read_file", toolResult.toolName());
        assertEquals("{\"ok\":true}", toolResult.text());
    }
}
