package com.arcclaw.persistence.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import com.arcclaw.persistence.api.SecretDto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FsSecretStoreAdapterTest {

    @Mock
    FilesystemFileStore store;

    @InjectMocks
    FsSecretStoreAdapter adapter;

    private static final byte[] CIPHERTEXT = new byte[] {0x01, 0x02, 0x03};
    private static final byte[] NONCE = new byte[] {0x0A, 0x0B};

    @Test
    void save_writesSecretRecord() {
        SecretDto dto = new SecretDto("sess-1", "api-key", 1, CIPHERTEXT, NONCE);

        adapter.save(dto);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> pathCaptor = ArgumentCaptor.forClass(List.class);
        verify(store).write(pathCaptor.capture(), any());

        List<String> path = pathCaptor.getValue();
        assertEquals(List.of("secrets", "sess-1", "api-key.json"), path);
    }

    @Test
    void find_returnsEmptyWhenNotPresent() {
        when(store.readOptional(anyList(), any())).thenReturn(Optional.empty());

        Optional<SecretDto> result = adapter.find("sess-1", "api-key");

        assertTrue(result.isEmpty());
    }

    @Test
    void find_returnsDtoWhenPresent() throws Exception {
        Object secretRecord = createSecretRecord(
                "sess-1",
                "api-key",
                1,
                Base64.getEncoder().encodeToString(CIPHERTEXT),
                Base64.getEncoder().encodeToString(NONCE));
        when(store.readOptional(anyList(), any())).thenReturn(Optional.of(secretRecord));

        Optional<SecretDto> result = adapter.find("sess-1", "api-key");

        assertTrue(result.isPresent());
        SecretDto dto = result.get();
        assertEquals("sess-1", dto.sessionId());
        assertEquals("api-key", dto.keyName());
        assertEquals(1, dto.encryptionKeyVersion());
        assertArrayEquals(CIPHERTEXT, dto.ciphertext());
        assertArrayEquals(NONCE, dto.nonce());
    }

    @Test
    void delete_removesSecretFile() {
        adapter.delete("sess-1", "api-key");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> pathCaptor = ArgumentCaptor.forClass(List.class);
        verify(store).delete(pathCaptor.capture());

        List<String> path = pathCaptor.getValue();
        assertEquals(List.of("secrets", "sess-1", "api-key.json"), path);
    }

    @Test
    void deleteBySessionId_removesDirectory() {
        adapter.deleteBySessionId("sess-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> pathCaptor = ArgumentCaptor.forClass(List.class);
        verify(store).deleteDirectory(pathCaptor.capture());

        List<String> path = pathCaptor.getValue();
        assertEquals(List.of("secrets", "sess-1"), path);
    }

    @Test
    void save_propagatesStoreException() {
        doThrow(new UncheckedIOException("disk full", new IOException()))
                .when(store)
                .write(anyList(), any());

        SecretDto dto = new SecretDto("sess-1", "api-key", 1, CIPHERTEXT, NONCE);
        assertThrows(UncheckedIOException.class, () -> adapter.save(dto));
    }

    // -- helper to construct the private SecretRecord via reflection --

    private Object createSecretRecord(
            String sessionId, String keyName, int version, String ciphertextB64, String nonceB64) throws Exception {
        Class<?> srClass = Class.forName("com.arcclaw.persistence.fs.FsSecretStoreAdapter$SecretRecord");
        Constructor<?> ctor =
                srClass.getDeclaredConstructor(String.class, String.class, int.class, String.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(sessionId, keyName, version, ciphertextB64, nonceB64);
    }
}
