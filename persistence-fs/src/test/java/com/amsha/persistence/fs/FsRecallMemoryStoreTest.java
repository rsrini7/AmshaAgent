package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.RecallMessageDto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FsRecallMemoryStoreTest {

    @Mock
    FilesystemFileStore store;

    @InjectMocks
    FsRecallMemoryStore recallStore;

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

    @Test
    void purgeExpired_deletesFileIfAllMessagesExpired() {
        String filename = "session-1.json";
        when(store.list(List.of("recall"), ".json")).thenReturn(List.of(filename));

        Instant expiredTime = Instant.now().minus(35, ChronoUnit.DAYS);

        RecallMessageDto expiredMsg1 = new RecallMessageDto(
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
        RecallMessageDto expiredMsg2 = new RecallMessageDto(
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
                expiredTime,
                null);

        FsRecallMemoryStore.MessageList initialList =
                new FsRecallMemoryStore.MessageList(List.of(expiredMsg1, expiredMsg2));

        when(store.readOptional(List.of("recall", filename), FsRecallMemoryStore.MessageList.class))
                .thenReturn(Optional.of(initialList));

        recallStore.purgeExpired("user-1", 30);

        verify(store).delete(List.of("recall", filename));
        verify(store, never()).write(any(), any());
    }

    @Test
    void purgeExpiredAll_filtersExpiredMessagesForAllUsers() {
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
        RecallMessageDto otherUserExpiredMsg = new RecallMessageDto(
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
                new FsRecallMemoryStore.MessageList(List.of(expiredMsg, activeMsg, otherUserExpiredMsg));

        when(store.readOptional(List.of("recall", filename), FsRecallMemoryStore.MessageList.class))
                .thenReturn(Optional.of(initialList));

        recallStore.purgeExpiredAll(30);

        ArgumentCaptor<FsRecallMemoryStore.MessageList> listCaptor =
                ArgumentCaptor.forClass(FsRecallMemoryStore.MessageList.class);
        verify(store).write(eq(List.of("recall", filename)), listCaptor.capture());

        List<RecallMessageDto> result = listCaptor.getValue().messages();
        assertEquals(1, result.size());
        assertTrue(result.contains(activeMsg));
    }
}
