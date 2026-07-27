package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.langchain4j.model.chat.ChatModel;

import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

class ChatModelPoolTest {

    @Test
    void getOrCreate_createsNewInstance() {
        ChatModelPool pool = new ChatModelPool();
        ChatModel mockModel = mock(ChatModel.class);

        ChatModel result = pool.getOrCreate("groq#llama-3.3", () -> mockModel);

        assertSame(mockModel, result);
        assertEquals(1, pool.size());
        assertTrue(pool.contains("groq#llama-3.3"));
    }

    @Test
    void getOrCreate_returnsSameInstanceForSameKey() {
        ChatModelPool pool = new ChatModelPool();
        ChatModel mockModel1 = mock(ChatModel.class);
        ChatModel mockModel2 = mock(ChatModel.class);

        ChatModel result1 = pool.getOrCreate("groq#llama-3.3", () -> mockModel1);
        ChatModel result2 = pool.getOrCreate("groq#llama-3.3", () -> mockModel2);

        assertSame(result1, result2);
        assertEquals(1, pool.size());
        // Second factory should NOT be called
    }

    @Test
    void getOrCreate_createsDifferentInstancesForDifferentKeys() {
        ChatModelPool pool = new ChatModelPool();
        ChatModel mockModel1 = mock(ChatModel.class);
        ChatModel mockModel2 = mock(ChatModel.class);

        ChatModel result1 = pool.getOrCreate("groq#llama-3.3", () -> mockModel1);
        ChatModel result2 = pool.getOrCreate("openai#gpt-4o", () -> mockModel2);

        assertNotSame(result1, result2);
        assertEquals(2, pool.size());
        assertTrue(pool.contains("groq#llama-3.3"));
        assertTrue(pool.contains("openai#gpt-4o"));
    }

    @Test
    void contains_returnsFalseForMissingKey() {
        ChatModelPool pool = new ChatModelPool();
        assertFalse(pool.contains("nonexistent#model"));
    }

    @Test
    void size_returnsZeroForEmptyPool() {
        ChatModelPool pool = new ChatModelPool();
        assertEquals(0, pool.size());
    }

    @Test
    void poolKey_formatsCorrectly() {
        assertEquals("groq#llama-3.3", ChatModelPool.poolKey("groq", "llama-3.3"));
        assertEquals("openai#gpt-4o", ChatModelPool.poolKey("openai", "gpt-4o"));
    }

    @Test
    void getOrCreate_doesNotCallFactoryWhenAlreadyPooled() {
        ChatModelPool pool = new ChatModelPool();
        Supplier<ChatModel> factory = mock(Supplier.class);
        ChatModel mockModel = mock(ChatModel.class);
        when(factory.get()).thenReturn(mockModel);

        pool.getOrCreate("groq#llama-3.3", factory);
        pool.getOrCreate("groq#llama-3.3", factory);

        // Factory should only be called once
        verify(factory, times(1)).get();
    }

    @Test
    void getOrCreateTyped_returnsSameInstanceForSameTypedKey() {
        ChatModelPool pool = new ChatModelPool();
        ChatModel m = mock(ChatModel.class);

        ChatModel r1 = pool.getOrCreateTyped("USER", "u1", "conn1", 1, "prof1", 1, "openai", "gpt-4o", () -> m);
        ChatModel r2 = pool.getOrCreateTyped("USER", "u1", "conn1", 1, "prof1", 1, "openai", "gpt-4o", () -> m);

        assertSame(r1, r2);
        // Single typed entry plus nothing else.
        assertEquals(1, pool.size());
    }

    @Test
    void getOrCreateTyped_separatesTwoAccountsForSameModel() {
        ChatModelPool pool = new ChatModelPool();
        ChatModel a = mock(ChatModel.class);
        ChatModel b = mock(ChatModel.class);

        ChatModel r1 = pool.getOrCreateTyped("USER", "u1", "conn1", 1, "prof1", 1, "openai", "gpt-4o", () -> a);
        ChatModel r2 = pool.getOrCreateTyped("USER", "u2", "conn2", 1, "prof2", 1, "openai", "gpt-4o", () -> b);

        assertNotSame(r1, r2);
        assertEquals(2, pool.size());
    }

    @Test
    void invalidateByConnection_removesAllDerivedEntries() {
        ChatModelPool pool = new ChatModelPool();
        ChatModel m = mock(ChatModel.class);
        pool.getOrCreateTyped("USER", "u1", "conn1", 1, "prof1", 1, "openai", "gpt-4o", () -> m);
        pool.getOrCreateTyped("USER", "u1", "conn1", 2, "prof2", 1, "openai", "gpt-4o", () -> m);

        pool.invalidateByConnection("conn1");

        assertEquals(0, pool.size());
        // Re-create after invalidation must build a fresh instance.
        ChatModel recreated = pool.getOrCreateTyped("USER", "u1", "conn1", 1, "prof1", 1, "openai", "gpt-4o", () -> m);
        assertSame(m, recreated);
    }

    @Test
    void invalidateByProfile_removesOnlyProfileDerivedEntry() {
        ChatModelPool pool = new ChatModelPool();
        ChatModel m = mock(ChatModel.class);
        pool.getOrCreateTyped("USER", "u1", "conn1", 1, "prof1", 1, "openai", "gpt-4o", () -> m);
        pool.getOrCreateTyped("USER", "u1", "conn1", 1, "prof2", 1, "openai", "gpt-4o", () -> m);

        pool.invalidateByProfile("prof1");

        assertEquals(1, pool.size());
    }

    @Test
    void typedKey_formatsAllComponents() {
        String key = ChatModelPool.typedKey("USER", "u1", "conn1", 3, "prof1", 2, "openai", "gpt-4o");
        assertEquals("USER#u1#conn1#3#prof1#2#openai#gpt-4o", key);
    }
}
