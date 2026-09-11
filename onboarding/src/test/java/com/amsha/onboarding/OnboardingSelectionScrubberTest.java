package com.amsha.onboarding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.core.routing.ModelProfileValidator;
import com.amsha.llm.api.LlmProviderOnboardingAdapter;
import com.amsha.llm.api.ProviderValidationResult;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultKey;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
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

    @Spy
    ModelProfileValidator profileValidator = new ModelProfileValidator();

    @InjectMocks
    OnboardingSelectionValidator validator;

    @InjectMocks
    OnboardingSelectionScrubber scrubber;

    @BeforeEach
    void setUp() {
        validator.objectMapper = objectMapper;
        validator.providerAdapter = providerAdapter;
        validator.profileValidator = profileValidator;
        scrubber.validator = validator;
    }

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
        AtomicReference<byte[]> stored = captureStoredSecret();
        List<String> output = scrubber.processAndScrubSecrets("user-1", input);

        assertEquals(1, output.size());
        assertTrue(output.get(0).contains("apiKeyVaultRef"));
        assertFalse(output.get(0).contains("secret-123"));
        // Captured at invocation time, before the caller zeroes the plaintext buffer.
        assertArrayEquals("secret-123".getBytes(StandardCharsets.UTF_8), stored.get());
        verify(vault).store(startsWith("vault-key-"), any(), eq("user-1"));
    }

    @Test
    void testProcessAndScrubSecretsZeroesPlaintextBufferAfterStore() {
        List<String> input = List.of("{\"connections\":[{\"provider\":\"openai\",\"apiKey\":\"secret-123\"}]}");
        AtomicReference<byte[]> bufferRef = new AtomicReference<>();
        doAnswer(invocation -> {
                    bufferRef.set(invocation.getArgument(1));
                    return null;
                })
                .when(vault)
                .store(anyString(), any(), anyString());

        scrubber.processAndScrubSecrets("user-1", input);

        byte[] buffer = bufferRef.get();
        assertNotNull(buffer);
        assertTrue(buffer.length > 0);
        // The caller must zero its plaintext buffer once the vault has consumed it.
        assertArrayEquals(new byte[buffer.length], buffer);
    }

    @Test
    void testProcessAndScrubSecretsPreservesNestedObjectUnderSecretNamedKey() {
        List<String> input =
                List.of("{\"security\":{\"tokenSettings\":{\"issuer\":\"acme\",\"refreshToken\":\"rt-live-9\"}}}");
        AtomicReference<byte[]> stored = captureStoredSecret();
        List<String> output = scrubber.processAndScrubSecrets("user-1", input);

        String scrubbed = output.get(0);
        assertFalse(scrubbed.contains("rt-live-9"));
        // The object is recursed into rather than dropped: non-secret siblings survive.
        assertTrue(scrubbed.contains("tokenSettings"));
        assertTrue(scrubbed.contains("acme"));
        assertTrue(scrubbed.contains("refreshTokenVaultRef"));
        assertArrayEquals("rt-live-9".getBytes(StandardCharsets.UTF_8), stored.get());
    }

    @Test
    void testProcessAndScrubSecretsVaultsNonStringValueUnderSecretNamedKey() {
        List<String> input = List.of("{\"security\":{\"apiKeys\":[\"alpha\",\"beta\"]}}");
        AtomicReference<byte[]> stored = captureStoredSecret();
        List<String> output = scrubber.processAndScrubSecrets("user-1", input);

        String scrubbed = output.get(0);
        assertFalse(scrubbed.contains("alpha"));
        assertFalse(scrubbed.contains("beta"));
        assertTrue(scrubbed.contains("apiKeysVaultRef"));
        assertArrayEquals("[\"alpha\",\"beta\"]".getBytes(StandardCharsets.UTF_8), stored.get());
    }

    /**
     * Clones the secret bytes at invocation time so assertions still see the plaintext after the
     * production code zeroes its buffer in a finally block.
     */
    private AtomicReference<byte[]> captureStoredSecret() {
        AtomicReference<byte[]> captured = new AtomicReference<>();
        doAnswer(invocation -> {
                    captured.set(((byte[]) invocation.getArgument(1)).clone());
                    return null;
                })
                .when(vault)
                .store(anyString(), any(), anyString());
        return captured;
    }

    @Test
    void testProcessAndScrubSecretsWithNestedSecrets() {
        List<String> input = List.of("{\"security\":{\"oauth\":{\"clientSecret\":\"sk-live-1\"}}}");
        List<String> output = scrubber.processAndScrubSecrets("user-1", input);

        assertEquals(1, output.size());
        assertFalse(output.get(0).contains("sk-live-1"));
        assertTrue(output.get(0).contains("clientSecretVaultRef"));
        verify(vault).store(startsWith("vault-key-"), any(), eq("user-1"));
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

    @Test
    void testValidateConnectionsWithRepeatableProfilesSuccess() {
        when(providerAdapter.validate(any())).thenReturn(ProviderValidationResult.ok());
        List<String> selections =
                List.of("{\"connections\":[{\"id\":\"conn-1\",\"provider\":\"openai\",\"apiKey\":\"k\"}],"
                        + "\"profiles\":["
                        + "{\"id\":\"prof-1\",\"connectionId\":\"conn-1\",\"modelId\":\"gpt-4o\",\"structuredOutputMode\":\"NATIVE\"},"
                        + "{\"id\":\"prof-2\",\"connectionId\":\"conn-1\",\"modelId\":\"gpt-4o-mini\",\"structuredOutputMode\":\"NATIVE\"}"
                        + "]}");
        String err = scrubber.validateConnections(selections);
        assertNull(err);
    }

    @Test
    void testValidateConnectionsRejectsProfileDraftWithUnsupportedAttachmentType() {
        when(providerAdapter.validate(any())).thenReturn(ProviderValidationResult.ok());
        List<String> selections =
                List.of("{\"connections\":[{\"id\":\"conn-1\",\"provider\":\"openai\",\"apiKey\":\"k\"}],"
                        + "\"profiles\":["
                        + "{\"id\":\"prof-1\",\"connectionId\":\"conn-1\",\"modelId\":\"gpt-4o\","
                        + "\"capabilityJson\":\"{\\\"attachments\\\":[{\\\"type\\\":\\\"UNSUPPORTED_TYPE\\\"}]}\"}"
                        + "]}");
        String err = scrubber.validateConnections(selections);
        assertNotNull(err);
        assertTrue(err.contains("UNSUPPORTED_TYPE"));
    }

    @Test
    void testValidateConnectionsRejectsProfileWithUnknownConnection() {
        when(providerAdapter.validate(any())).thenReturn(ProviderValidationResult.ok());
        List<String> selections =
                List.of("{\"connections\":[{\"id\":\"conn-1\",\"provider\":\"openai\",\"apiKey\":\"k\"}],"
                        + "\"profiles\":["
                        + "{\"id\":\"prof-1\",\"connectionId\":\"conn-unknown\",\"modelId\":\"gpt-4o\"}"
                        + "]}");
        String err = scrubber.validateConnections(selections);
        assertNotNull(err);
        assertTrue(err.contains("unknown connectionId"));
    }

    @Test
    void testValidateConnectionsProviderAdapterNullReturnsError() {
        OnboardingSelectionValidator unconfigured = new OnboardingSelectionValidator();
        String err = unconfigured.validateConnections(List.of("{\"connections\":[]}"));
        assertEquals("LlmProviderOnboardingAdapter is unavailable", err);
    }

    @Test
    void testValidateConnectionsInvalidJsonReturnsError() {
        String err = scrubber.validateConnections(List.of("not-a-valid-json with sk-live-secret-key-123"));
        assertNotNull(err);
        assertTrue(err.contains("Invalid selection payload"));
        assertFalse(err.contains("sk-live-secret-key-123"));
    }

    @Test
    void testValidateConnectionsNonListConnectionsReturnsError() {
        String err = scrubber.validateConnections(List.of("{\"connections\":\"not-a-list\"}"));
        assertNotNull(err);
        assertTrue(err.contains("connections must be a list"));
    }

    @Test
    void testValidateConnectionsNonObjectConnectionEntryReturnsError() {
        String err = scrubber.validateConnections(List.of("{\"connections\":[\"not-a-map\"]}"));
        assertNotNull(err);
        assertTrue(err.contains("connection entry must be a JSON object"));
    }

    @Test
    void testValidateConnectionsNonListProfilesReturnsError() {
        String err = scrubber.validateConnections(List.of("{\"profiles\":\"not-a-list\"}"));
        assertNotNull(err);
        assertTrue(err.contains("profiles must be a list"));
    }

    @Test
    void testValidateConnectionsMissingConnectionsWithProfilesValidatesAgainstEmpty() {
        List<String> selections = List.of(
                "{\"profiles\":[" + "{\"id\":\"prof-1\",\"connectionId\":\"conn-1\",\"modelId\":\"gpt-4o\"}" + "]}");
        String err = scrubber.validateConnections(selections);
        assertNotNull(err);
        assertTrue(err.contains("unknown connectionId"));
    }

    @Test
    void testValidateConnectionsCrossSelectionResolvesConnectionId() {
        when(providerAdapter.validate(any())).thenReturn(ProviderValidationResult.ok());
        List<String> selections = List.of(
                "{\"connections\":[{\"id\":\"conn-1\",\"provider\":\"openai\",\"apiKey\":\"k\"}]}",
                "{\"profiles\":[{\"id\":\"prof-1\",\"connectionId\":\"conn-1\",\"modelId\":\"gpt-4o\"}]}");
        String err = scrubber.validateConnections(selections);
        assertNull(err);
    }
}
