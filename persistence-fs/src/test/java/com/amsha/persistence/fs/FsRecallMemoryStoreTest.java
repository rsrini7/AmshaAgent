package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.RecallMessageDto;
import com.amsha.persistence.api.RecallSearchQuery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FsRecallMemoryStoreTest {

    @Mock
    FilesystemFileStore store;

    @InjectMocks
    FsRecallMemoryStore recallStore;

    @Test
    void testInsertOrIgnore() {
        RecallMessageDto msg = new RecallMessageDto(
                "msg-1",
                "sess-1",
                "usr-1",
                1,
                "hash-1",
                "user",
                "USER_MESSAGE",
                "hello test",
                null,
                null,
                false,
                false,
                Instant.now(),
                null);

        when(store.readOptional(List.of("recall", "sess-1.json"), FsRecallMemoryStore.MessageList.class))
                .thenReturn(Optional.empty());

        recallStore.insertOrIgnore(msg);
        verify(store).write(eq(List.of("recall", "sess-1.json")), any(FsRecallMemoryStore.MessageList.class));
    }

    @Test
    void testInsertBatch() {
        RecallMessageDto msg1 = new RecallMessageDto(
                "msg-1",
                "sess-1",
                "usr-1",
                1,
                "hash-1",
                "user",
                "USER_MESSAGE",
                "hello",
                null,
                null,
                false,
                false,
                Instant.now(),
                null);
        RecallMessageDto msg2 = new RecallMessageDto(
                "msg-2",
                "sess-2",
                "usr-1",
                1,
                "hash-2",
                "user",
                "USER_MESSAGE",
                "world",
                null,
                null,
                false,
                false,
                Instant.now(),
                null);

        recallStore.insertBatch(List.of(msg1, msg2));
        verify(store, times(2)).write(any(), any());
    }

    @Test
    void testSearchAndGetById() {
        RecallMessageDto msg = new RecallMessageDto(
                "msg-1",
                "sess-1",
                "usr-1",
                1,
                "hash-1",
                "user",
                "USER_MESSAGE",
                "hello test",
                null,
                null,
                false,
                false,
                Instant.now(),
                null);
        when(store.readOptional(any(), eq(FsRecallMemoryStore.MessageList.class)))
                .thenReturn(Optional.of(new FsRecallMemoryStore.MessageList(List.of(msg))));

        RecallSearchQuery query = new RecallSearchQuery("usr-1", "test", "sess-1", 10, true, null);
        var results = recallStore.search(query);
        assertEquals(1, results.size());

        when(store.list(List.of("recall"), ".json")).thenReturn(List.of("sess-1.json"));
        Optional<RecallMessageDto> found = recallStore.getById("msg-1");
        assertTrue(found.isPresent());
    }

    @Test
    void purgeExpired_noMessages_doesNothing() {
        when(store.list(List.of("recall"), ".json")).thenReturn(List.of());

        recallStore.purgeExpired("user-1", 30);

        verify(store, never()).delete(any());
        verify(store, never()).write(any(), any());
    }

    @Test
    void purgeExpired_filtersExpiredMessagesForUser() {
        String filename = "session-1.json";
        when(store.list(List.of("recall"), ".json")).thenReturn(List.of(filename));

        Instant now = Instant.now();
        Instant expiredTime = now.minus(31, ChronoUnit.DAYS);
        Instant activeTime = now.minus(10, ChronoUnit.DAYS);

        RecallMessageDto expiredMsg = new RecallMessageDto(
                "msg-1",
                "session-1",
                "user-1",
                1,
                "hash-1",
                "user",
                "USER_MESSAGE",
                "hello",
                null,
                null,
                false,
                false,
                expiredTime,
                null);
        RecallMessageDto activeMsg = new RecallMessageDto(
                "msg-2",
                "session-1",
                "user-1",
                2,
                "hash-2",
                "agent",
                "AGENT_MESSAGE",
                "world",
                null,
                null,
                false,
                false,
                activeTime,
                null);
        RecallMessageDto otherUserMsg = new RecallMessageDto(
                "msg-3",
                "session-1",
                "user-2",
                3,
                "hash-3",
                "user",
                "USER_MESSAGE",
                "other",
                null,
                null,
                false,
                false,
                expiredTime,
                null);

        FsRecallMemoryStore.MessageList initialList =
                new FsRecallMemoryStore.MessageList(List.of(expiredMsg, activeMsg, otherUserMsg));

        when(store.readOptional(List.of("recall", filename), FsRecallMemoryStore.MessageList.class))
                .thenReturn(Optional.of(initialList));

        recallStore.purgeExpired("user-1", 30);

        ArgumentCaptor<FsRecallMemoryStore.MessageList> listCaptor =
                ArgumentCaptor.forClass(FsRecallMemoryStore.MessageList.class);
        verify(store).write(eq(List.of("recall", filename)), listCaptor.capture());

        List<RecallMessageDto> result = listCaptor.getValue().messages();
        assertEquals(2, result.size());
        assertTrue(result.contains(activeMsg));
        assertTrue(result.contains(otherUserMsg));
    }
}
