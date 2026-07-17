package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.core.security.SecretVault;
import com.amsha.core.security.VaultKey;
import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.persistence.api.LlmProfileStorePort;
import com.amsha.persistence.api.ModelRouteAssignmentDto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SystemProfileMigrationTest {

    @Mock
    SecretVault vault;

    @Mock
    LlmProfileStorePort profileStore;

    @InjectMocks
    SystemProfileMigration migration;

    private VaultKey key(String v) {
        return new VaultKey(v.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void migratesWhenLegacyKeysPresent() {
        when(profileStore.listRoutesByScope("SYSTEM", "SYSTEM")).thenReturn(List.of());
        when(vault.retrieveSystemKey("llm.provider.key")).thenReturn(Optional.of(key("groq")));
        when(vault.retrieveSystemKey("llm.model")).thenReturn(Optional.of(key("llama-3.1-8b")));
        when(vault.systemKeyExists("llm.api.key")).thenReturn(true);

        migration.migrateIfNeeded();

        ArgumentCaptor<LlmConnectionDto> connCaptor = ArgumentCaptor.forClass(LlmConnectionDto.class);
        verify(profileStore).saveConnection(connCaptor.capture());
        assertEquals("SYSTEM", connCaptor.getValue().scopeType());
        verify(profileStore).saveModelProfile(any());
        verify(profileStore).saveRouteAssignment(any());
        verify(profileStore).saveSecretRef(any());
    }

    @Test
    void skipsWhenSystemRouteAlreadyExists() {
        when(profileStore.listRoutesByScope("SYSTEM", "SYSTEM"))
                .thenReturn(List.of(new ModelRouteAssignmentDto(
                        "r", "SYSTEM", "SYSTEM", "DEFAULT", null, List.of(), true, 1, Instant.now(), Instant.now())));

        migration.migrateIfNeeded();

        verify(profileStore, never()).saveConnection(any());
        verify(profileStore, never()).saveRouteAssignment(any());
    }

    @Test
    void skipsWhenLegacyApiKeyMissing() {
        when(profileStore.listRoutesByScope("SYSTEM", "SYSTEM")).thenReturn(List.of());
        when(vault.retrieveSystemKey("llm.provider.key")).thenReturn(Optional.of(key("groq")));
        when(vault.retrieveSystemKey("llm.model")).thenReturn(Optional.of(key("llama-3.1-8b")));
        when(vault.systemKeyExists("llm.api.key")).thenReturn(false);

        migration.migrateIfNeeded();

        verify(profileStore, never()).saveConnection(any());
        verify(profileStore, never()).saveRouteAssignment(any());
    }

    @Test
    void migratesWithDefaultModelWhenModelKeyAbsent() {
        when(profileStore.listRoutesByScope("SYSTEM", "SYSTEM")).thenReturn(List.of());
        when(vault.retrieveSystemKey("llm.provider.key")).thenReturn(Optional.of(key("openai")));
        when(vault.retrieveSystemKey("llm.model")).thenReturn(Optional.empty());
        when(vault.systemKeyExists("llm.api.key")).thenReturn(true);

        migration.migrateIfNeeded();

        verify(profileStore, times(1)).saveConnection(any());
        verify(profileStore).saveRouteAssignment(any());
    }
}
