package com.amsha.core.speech;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.enterprise.inject.Instance;

import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

import com.amsha.memory.tiered.TieredBackendLiteral;
import com.amsha.persistence.api.CoreMemoryBlockDto;
import com.amsha.persistence.api.CoreMemoryStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserPreferenceServiceImplTest {

    @Mock
    Instance<CoreMemoryStorePort> coreMemoryStoreInstance;

    @Mock
    Instance<CoreMemoryStorePort> backendSpecificInstance;

    @Mock
    CoreMemoryStorePort coreMemoryStore;

    private UserPreferenceServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(coreMemoryStoreInstance.isUnsatisfied()).thenReturn(false);
        lenient().when(coreMemoryStoreInstance.isResolvable()).thenReturn(true);
        lenient().when(coreMemoryStoreInstance.get()).thenReturn(coreMemoryStore);
        lenient()
                .when(coreMemoryStoreInstance.select(any(TieredBackendLiteral.class)))
                .thenReturn(backendSpecificInstance);
        lenient().when(backendSpecificInstance.isResolvable()).thenReturn(true);
        lenient().when(backendSpecificInstance.get()).thenReturn(coreMemoryStore);
        lenient().when(coreMemoryStoreInstance.stream()).thenAnswer(inv -> Stream.of(coreMemoryStore));
        service = new UserPreferenceServiceImpl(
                coreMemoryStoreInstance, Optional.of("female_1"), OutputMode.TEXT_ONLY, "sqlite");
    }

    @Test
    void testGetVoiceWhenPresent() {
        Instant now = Instant.now();
        when(coreMemoryStore.get("u-1", "pref:voice"))
                .thenReturn(Optional.of(new CoreMemoryBlockDto("b-1", "u-1", "pref:voice", "vivian", 1, now, now)));

        Optional<String> voice = service.getVoice("u-1");
        assertTrue(voice.isPresent());
        assertEquals("vivian", voice.get());
        assertEquals("vivian", service.getEffectiveVoice("u-1").orElse(null));
    }

    @Test
    void testGetVoiceFallbackToConfigured() {
        when(coreMemoryStore.get("u-1", "pref:voice")).thenReturn(Optional.empty());

        Optional<String> voice = service.getVoice("u-1");
        assertTrue(voice.isEmpty());
        assertEquals("female_1", service.getEffectiveVoice("u-1").orElse(null));
    }

    @Test
    void testSetVoice() {
        Instant now = Instant.now();
        when(coreMemoryStore.get("u-1", "pref:voice"))
                .thenReturn(Optional.of(new CoreMemoryBlockDto("b-1", "u-1", "pref:voice", "old", 2, now, now)));

        service.setVoice("u-1", "serena");
        verify(coreMemoryStore).upsert("u-1", "pref:voice", "serena", 2);
    }

    @Test
    void testSetVoiceDefaultClears() {
        service.setVoice("u-1", "default");
        verify(coreMemoryStore).delete("u-1", "pref:voice");
    }

    @Test
    void testSetVoiceInvalidThrows() {
        assertThrows(IllegalArgumentException.class, () -> service.setVoice("u-1", "arbitrary_unsupported_voice"));
    }

    @Test
    void testOutputModePreference() {
        Instant now = Instant.now();
        when(coreMemoryStore.get("u-1", "pref:speech_output_mode"))
                .thenReturn(Optional.of(
                        new CoreMemoryBlockDto("b-2", "u-1", "pref:speech_output_mode", "AUDIO_ONLY", 1, now, now)));

        Optional<String> mode = service.getOutputMode("u-1");
        assertTrue(mode.isPresent());
        assertEquals("AUDIO_ONLY", mode.get());
        assertEquals("AUDIO_ONLY", service.getEffectiveOutputMode("u-1").orElse(null));
    }

    @Test
    void testSetOutputModeValid() {
        Instant now = Instant.now();
        when(coreMemoryStore.get("u-1", "pref:speech_output_mode"))
                .thenReturn(Optional.of(
                        new CoreMemoryBlockDto("b-2", "u-1", "pref:speech_output_mode", "TEXT_ONLY", 1, now, now)));

        service.setOutputMode("u-1", "TEXT_AND_AUDIO");
        verify(coreMemoryStore).upsert("u-1", "pref:speech_output_mode", "TEXT_AND_AUDIO", 1);
    }

    @Test
    void testSetOutputModeDefaultClears() {
        service.setOutputMode("u-1", "default");
        verify(coreMemoryStore).delete("u-1", "pref:speech_output_mode");
    }

    @Test
    void testSetOutputModeInvalidThrows() {
        assertThrows(IllegalArgumentException.class, () -> service.setOutputMode("u-1", "INVALID_MODE"));
    }

    @Test
    void testUpdatePreferencesAtomicValid() {
        Instant now = Instant.now();
        when(coreMemoryStore.get("u-1", "pref:voice"))
                .thenReturn(Optional.of(new CoreMemoryBlockDto("b-1", "u-1", "pref:voice", "old", 2, now, now)));
        when(coreMemoryStore.get("u-1", "pref:speech_output_mode"))
                .thenReturn(Optional.of(
                        new CoreMemoryBlockDto("b-2", "u-1", "pref:speech_output_mode", "TEXT_ONLY", 1, now, now)));

        service.updatePreferences("u-1", "vivian", "AUDIO_ONLY");
        verify(coreMemoryStore).upsert("u-1", "pref:voice", "vivian", 2);
        verify(coreMemoryStore).upsert("u-1", "pref:speech_output_mode", "AUDIO_ONLY", 1);
    }

    @Test
    void testUpdatePreferencesAtomicInvalidVoiceRejectsWithoutMutation() {
        assertThrows(
                IllegalArgumentException.class, () -> service.updatePreferences("u-1", "invalid_voice", "AUDIO_ONLY"));
        verify(coreMemoryStore, never()).upsert(anyString(), anyString(), anyString(), anyInt());
        verify(coreMemoryStore, never()).delete(anyString(), anyString());
    }

    @Test
    void testUpdatePreferencesAtomicInvalidModeRejectsWithoutMutation() {
        assertThrows(IllegalArgumentException.class, () -> service.updatePreferences("u-1", "vivian", "INVALID_MODE"));
        verify(coreMemoryStore, never()).upsert(anyString(), anyString(), anyString(), anyInt());
        verify(coreMemoryStore, never()).delete(anyString(), anyString());
    }
}
