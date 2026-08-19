package com.amsha.llm.providers;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;

import com.amsha.core.routing.MultiModelChatModel;

import org.junit.jupiter.api.Test;

class ChatModelFactoryMultiModelTest {

    private static ChatModel mockModel(String id) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return null;
            }

            @Override
            public String toString() {
                return "mock-" + id;
            }
        };
    }

    @Test
    void singleKey_singleModel_oneEntry() {
        List<MultiModelChatModel.ModelEntry> entries =
                ChatModelFactory.buildMultiModelEntries("key1", "model-a", "test", (k, m) -> mockModel(k + "/" + m));

        assertEquals(1, entries.size());
        assertEquals("test", entries.get(0).label());
    }

    @Test
    void multipleKeys_singleModel_oneEntryPerKey() {
        List<MultiModelChatModel.ModelEntry> entries = ChatModelFactory.buildMultiModelEntries(
                "key1,key2,key3", "model-a", "test", (k, m) -> mockModel(k + "/" + m));

        assertEquals(3, entries.size());
        assertEquals("test-key1", entries.get(0).label());
        assertEquals("test-key2", entries.get(1).label());
        assertEquals("test-key3", entries.get(2).label());
    }

    @Test
    void singleKey_multipleModels_oneEntryPerModel() {
        List<MultiModelChatModel.ModelEntry> entries = ChatModelFactory.buildMultiModelEntries(
                "key1", "model-a,model-b", "test", (k, m) -> mockModel(k + "/" + m));

        assertEquals(2, entries.size());
        assertEquals("test-model-a", entries.get(0).label());
        assertEquals("test-model-b", entries.get(1).label());
    }

    @Test
    void multipleKeys_multipleModels_pairedByIndex() {
        List<MultiModelChatModel.ModelEntry> entries = ChatModelFactory.buildMultiModelEntries(
                "key1,key2", "model-a,model-b", "test", (k, m) -> mockModel(k + "/" + m));

        assertEquals(2, entries.size());
        assertEquals("test-key1/model-a", entries.get(0).label());
        assertEquals("test-key2/model-b", entries.get(1).label());
    }

    @Test
    void mismatchedCounts_pairsByShorter() {
        List<MultiModelChatModel.ModelEntry> entries = ChatModelFactory.buildMultiModelEntries(
                "key1,key2,key3", "model-a,model-b", "test", (k, m) -> mockModel(k + "/" + m));

        assertEquals(2, entries.size()); // min(3, 2) = 2
    }

    @Test
    void whitespaceIsTrimmed() {
        List<MultiModelChatModel.ModelEntry> entries = ChatModelFactory.buildMultiModelEntries(
                " key1 , key2 ", " model-a , model-b ", "test", (k, m) -> mockModel(k + "/" + m));

        assertEquals(2, entries.size());
        assertEquals("test-key1/model-a", entries.get(0).label());
        assertEquals("test-key2/model-b", entries.get(1).label());
    }

    @Test
    void resultIsNeverEmpty() {
        List<MultiModelChatModel.ModelEntry> entries =
                ChatModelFactory.buildMultiModelEntries("key1", "model-a", "test", (k, m) -> mockModel(k + "/" + m));

        assertFalse(entries.isEmpty());
    }
}
