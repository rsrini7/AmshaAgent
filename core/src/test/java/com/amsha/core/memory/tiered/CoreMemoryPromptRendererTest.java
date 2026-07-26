package com.amsha.core.memory.tiered;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;

import com.amsha.persistence.api.CoreMemoryBlockDto;

import org.junit.jupiter.api.Test;

class CoreMemoryPromptRendererTest {

    @Test
    void rendersKnownAndCustomLabelsWhileSkippingBlankBlocks() {
        CoreMemoryPromptRenderer renderer = new CoreMemoryPromptRenderer();
        assertNull(renderer.render(null));
        assertNull(renderer.render(List.of()));
        assertNull(renderer.render(List.of(block("active_task", " "))));

        String rendered = renderer.render(List.of(
                block("user_profile", "Ada"),
                block("active_task", "Ship"),
                block("constraints", "Safe"),
                block("project_context", "Amsha"),
                block("preferences", "Concise"),
                block("custom", "Value")));
        assertEquals(
                "User: Ada\nActive Task: Ship\nConstraints: Safe\nProject Context: Amsha\nPreferences: Concise\ncustom: Value",
                rendered);
    }

    @Test
    void noOpStoreHonorsTheDisabledContract() {
        NoOpCoreMemoryStore store = new NoOpCoreMemoryStore();
        assertTrue(store.read("user").isEmpty());
        assertTrue(store.get("user", "key").isEmpty());
        assertDoesNotThrow(() -> store.upsert("user", "key", "value", 0));
        assertDoesNotThrow(() -> store.delete("user", "key"));
    }

    @Test
    void backendIdsRoundTripAndRejectUnknownValues() {
        assertEquals(TieredMemoryBackend.SQLITE, TieredMemoryBackend.fromId("SQLITE"));
        assertEquals(TieredMemoryBackend.POSTGRES, TieredMemoryBackend.fromId("postgres"));
        assertEquals(TieredMemoryBackend.FILESYSTEM, TieredMemoryBackend.fromId("fs"));
        assertEquals("fs", TieredMemoryBackend.FILESYSTEM.id());
        assertThrows(IllegalArgumentException.class, () -> TieredMemoryBackend.fromId("missing"));
    }

    private static CoreMemoryBlockDto block(String key, String value) {
        return new CoreMemoryBlockDto("id-" + key, "user", key, value, 1, Instant.now(), Instant.now());
    }
}
