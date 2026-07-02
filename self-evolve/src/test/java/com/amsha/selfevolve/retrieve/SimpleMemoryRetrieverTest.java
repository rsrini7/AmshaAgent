package com.amsha.selfevolve.retrieve;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import com.amsha.selfevolve.api.ExtractedMemory;
import com.amsha.selfevolve.api.MemoryRecordType;
import com.amsha.selfevolve.store.noop.NoOpExtractedMemoryStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SimpleMemoryRetrieverTest {

    private SimpleMemoryRetriever retriever;

    @BeforeEach
    void setUp() {
        retriever = new SimpleMemoryRetriever(new NoOpExtractedMemoryStore());
    }

    @Test
    void getByType_nullType_returnsEmpty() {
        assertTrue(retriever.getByType(null, 5).isEmpty());
    }

    @Test
    void getByType_zeroLimit_returnsEmpty() {
        assertTrue(retriever.getByType(MemoryRecordType.FAILURE_PATTERN, 0).isEmpty());
    }

    @Test
    void getBySession_nullSession_returnsEmpty() {
        assertTrue(retriever.getBySession(null, 5).isEmpty());
    }

    @Test
    void getBySession_blankSession_returnsEmpty() {
        assertTrue(retriever.getBySession("", 5).isEmpty());
    }

    @Test
    void getByUser_nullUser_returnsEmpty() {
        assertTrue(retriever.getByUser(null, 5).isEmpty());
    }

    @Test
    void getByUser_blankUser_returnsEmpty() {
        assertTrue(retriever.getByUser("", 5).isEmpty());
    }

    @Test
    void getByType_withNoOpStore_returnsEmpty() {
        List<ExtractedMemory> results = retriever.getByType(MemoryRecordType.FAILURE_PATTERN, 10);
        assertTrue(results.isEmpty());
    }

    @Test
    void getBySession_withNoOpStore_returnsEmpty() {
        List<ExtractedMemory> results = retriever.getBySession("session1", 10);
        assertTrue(results.isEmpty());
    }

    @Test
    void getByUser_withNoOpStore_returnsEmpty() {
        List<ExtractedMemory> results = retriever.getByUser("user1", 10);
        assertTrue(results.isEmpty());
    }
}
