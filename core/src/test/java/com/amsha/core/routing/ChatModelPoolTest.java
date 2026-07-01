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
}
