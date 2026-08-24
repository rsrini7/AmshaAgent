package com.amsha.memory.tiered;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.List;

import org.junit.jupiter.api.Test;

class RecallMessageMapperTest {

    @Test
    void mapsEverySupportedRoleAndDetectsContentTraits() {
        var messages = List.of(
                UserMessage.from("please fix this"),
                AiMessage.from("class Example { }"),
                SystemMessage.from("Exception: failed"));

        var result = RecallMessageMapper.map("session", "user", messages, 7);

        assertAll(
                () -> assertEquals(
                        List.of(7, 8, 9),
                        result.stream().map(r -> r.turnNumber()).toList()),
                () -> assertEquals(
                        List.of("user", "agent", "system"),
                        result.stream().map(r -> r.role()).toList()),
                () -> assertEquals(
                        List.of("USER_MESSAGE", "AGENT_MESSAGE", "SYSTEM_NOTE"),
                        result.stream().map(r -> r.messageType()).toList()),
                () -> assertFalse(result.getFirst().hasCode()),
                () -> assertTrue(result.get(1).hasCode()),
                () -> assertTrue(result.getLast().hasError()),
                () -> assertEquals(64, result.getFirst().contentHash().length()));
    }

    @Test
    void handlesEmptyAiContentAndCreatesCompactionSummary() {
        var empty = RecallMessageMapper.map("session", "user", List.of(AiMessage.from("")))
                .getFirst();
        var summary = RecallMessageMapper.compactionSummary("session", "user", 12, "summary");

        assertAll(
                () -> assertEquals(
                        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", empty.contentHash()),
                () -> assertEquals("COMPACTION_SUMMARY", summary.messageType()),
                () -> assertEquals("system", summary.role()),
                () -> assertEquals(12, summary.turnNumber()),
                () -> assertFalse(summary.hasCode()),
                () -> assertFalse(summary.hasError()));
    }
}
