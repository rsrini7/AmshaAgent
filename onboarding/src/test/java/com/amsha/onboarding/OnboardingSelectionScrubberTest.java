package com.amsha.onboarding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import com.amsha.core.security.SecretVault;
import com.amsha.core.security.VaultKey;
import com.amsha.llm.api.LlmProviderOnboardingAdapter;
import com.amsha.llm.api.ProviderValidationResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OnboardingSelectionScrubberTest {

    @Mock
    SecretVault vault;

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    LlmProviderOnboardingAdapter providerAdapter;

    @InjectMocks
    OnboardingSelectionScrubber scrubber;

    @Test
    void testProcessAndScrubSecretsNoSecrets() {
        List<String> input = List.of("{\"step\":\"1\",\"connections\":[]}");
        List<String> output = scrubber.processAndScrubSecrets("user-1", input);
        assertEquals(1, output.size());
        verify(vault, never()).store(anyString(), any(), anyString());
    }

    @Test
    void testProcessAndScrubSecretsWithApiKey() {
        List<String> input = List.of("{\"connections\":[{\"provider\":\"openai\",\"apiKey\":\"secret-123\"}]}");
        List<String> output = scrubber.processAndScrubSecrets("user-1", input);

        assertEquals(1, output.size());
        assertTrue(output.get(0).contains("apiKeyVaultRef"));
        assertFalse(output.get(0).contains("secret-123"));
        verify(vault).store(anyString(), eq("secret-123".getBytes(StandardCharsets.UTF_8)), eq("user-1"));
    }

    @Test
    void testExtractExtensionPayload() {
        List<String> selections = List.of("{\"mcp\":{\"enabled\":true}}");
        String payload = scrubber.extractExtensionPayload(selections, "mcp");
        assertEquals("{\"enabled\":true}", payload);
    }

    @Test
    void testValidateConnectionsSuccess() {
        when(providerAdapter.validate(any())).thenReturn(ProviderValidationResult.ok());
        List<String> selections = List.of("{\"connections\":[{\"provider\":\"openai\",\"apiKey\":\"k\"}]}");
        String err = scrubber.validateConnections(selections);
        assertNull(err);
    }

    @Test
    void scrubsAuthenticationHeadersForEveryMcpServer() {
        List<String> selections = List.of("{\"mcp\":{\"servers\":["
                + "{\"name\":\"one\",\"headers\":{\"Authorization\":\"Bearer one\"}},"
                + "{\"name\":\"two\",\"headers\":{\"X-Token\":\"two\"}}]}}");

        List<String> scrubbed = scrubber.processAndScrubSecrets("user-1", selections);

        assertFalse(scrubbed.get(0).contains("Bearer one"));
        assertFalse(scrubbed.get(0).contains("\"X-Token\":\"two\""));
        assertTrue(scrubbed.get(0).contains("headerVaultRefs"));
        verify(vault, times(2)).store(startsWith("mcp-header-"), any(), eq("user-1"));
    }

    @Test
    void resolvesAuthenticationHeadersForEveryMcpServer() {
        String payload = "{\"servers\":["
                + "{\"name\":\"one\",\"headerVaultRefs\":{\"Authorization\":\"ref-one\"}},"
                + "{\"name\":\"two\",\"headerVaultRefs\":{\"X-Token\":\"ref-two\"}}]}";
        when(vault.get(eq("ref-one"), eq("user-1")))
                .thenReturn(Optional.of(new VaultKey("Bearer one".getBytes(StandardCharsets.UTF_8))));
        when(vault.get(eq("ref-two"), eq("user-1")))
                .thenReturn(Optional.of(new VaultKey("two".getBytes(StandardCharsets.UTF_8))));

        String resolved = scrubber.resolveVaultReferences("user-1", payload);

        assertTrue(resolved.contains("Bearer one"));
        assertTrue(resolved.contains("\"X-Token\":\"two\""));
        assertFalse(resolved.contains("headerVaultRefs"));
    }
}
