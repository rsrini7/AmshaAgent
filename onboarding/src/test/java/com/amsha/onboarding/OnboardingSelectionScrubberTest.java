package com.amsha.onboarding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.core.routing.ModelProfileValidator;
import com.amsha.llm.api.LlmProviderOnboardingAdapter;
import com.amsha.llm.api.ProviderValidationResult;
import com.amsha.persistence.api.OnboardingDraftDto;
import com.amsha.persistence.api.OnboardingDraftPurpose;
import com.amsha.persistence.api.OnboardingDraftState;
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

    // ---- staged-secret cleanup ----

    private static OnboardingDraftDto draftWithSelections(List<String> selections) {
        Instant now = Instant.parse("2026-10-02T00:00:00Z");
        return new OnboardingDraftDto(
                "draft-1",
                "user-1",
                OnboardingDraftPurpose.EXTENSION_SETUP,
                "plan-1",
                "step-1",
                List.of(),
                selections,
                null,
                null,
                OnboardingDraftState.DRAFT,
                1,
                now.plusSeconds(3600),
                now,
                now);
    }

    @Test
    void cleanupStagedSecretsReturnsEarlyWithoutSelections() {
        scrubber.cleanupStagedSecrets(draftWithSelections(null));
        scrubber.cleanupStagedSecrets(draftWithSelections(List.of()));

        verifyNoInteractions(vault);
    }

    @Test
    void cleanupStagedSecretsSkipsSelectionsThatAreNotJsonObjects() {
        // Cleanup runs on discard/expiry/denial, so a corrupt staged selection must be skipped, not thrown.
        scrubber.cleanupStagedSecrets(draftWithSelections(List.of("not-json")));

        verifyNoInteractions(vault);
    }

    @Test
    void cleanupStagedSecretsRemovesEveryStagedVaultReference() {
        scrubber.cleanupStagedSecrets(
                draftWithSelections(List.of("{\"security\":{\"apiKeyVaultRef\":\"vault-key-aaa\"},"
                        + "\"connections\":[{\"apiKeyVaultRef\":\"vault-key-bbb\"}]}")));

        verify(vault).remove("vault-key-aaa", "user-1");
        verify(vault).remove("vault-key-bbb", "user-1");
    }

    @Test
    void cleanupStagedSecretsRemovesADuplicatedReferenceOnlyOnce() {
        scrubber.cleanupStagedSecrets(draftWithSelections(
                List.of("{\"security\":{\"apiKeyVaultRef\":\"vault-key-dup\",\"tokenRef\":\"vault-key-dup\"}}")));

        verify(vault, times(1)).remove("vault-key-dup", "user-1");
    }

    @Test
    void cleanupStagedSecretsCleansHeaderReferencesOnEveryMcpServer() {
        scrubber.cleanupStagedSecrets(draftWithSelections(List.of("{\"mcp\":{\"servers\":["
                + "{\"headerVaultRefs\":{\"Authorization\":\"mcp-header-aaa\"}},"
                + "{\"headerVaultRefs\":{\"X-Token\":\"mcp-header-bbb\"}}]}}")));

        verify(vault).remove("mcp-header-aaa", "user-1");
        verify(vault).remove("mcp-header-bbb", "user-1");
    }

    @Test
    void cleanupStagedSecretsCleansTopLevelMcpHeaderReferences() {
        scrubber.cleanupStagedSecrets(
                draftWithSelections(List.of("{\"mcp\":{\"headerVaultRefs\":{\"Authorization\":\"mcp-header-ccc\"}}}")));

        verify(vault).remove("mcp-header-ccc", "user-1");
    }

    @Test
    void cleanupStagedSecretsToleratesAFailingVaultRemoval() {
        doThrow(new IllegalStateException("vault unavailable")).when(vault).remove(anyString(), anyString());

        // Every cleanup site swallows a vault failure: cleanup is best-effort and must not break discard.
        assertDoesNotThrow(() -> scrubber.cleanupStagedSecrets(
                draftWithSelections(List.of("{\"security\":{\"apiKeyVaultRef\":\"vault-key-eee\"},"
                        + "\"mcp\":{\"headerVaultRefs\":{\"Authorization\":\"mcp-header-fff\"},"
                        + "\"servers\":[{\"headerVaultRefs\":{\"X-Token\":\"mcp-header-ggg\"}}]}}"))));

        verify(vault, atLeastOnce()).remove(anyString(), anyString());
    }

    // ---- resolveVaultReferences ----

    @Test
    void resolveVaultReferencesResolvesHeadersNestedUnderMcp() {
        when(vault.get("mcp-header-nested", "user-1"))
                .thenReturn(Optional.of(new VaultKey("Bearer nested".getBytes(StandardCharsets.UTF_8))));

        String resolved = scrubber.resolveVaultReferences(
                "user-1", "{\"mcp\":{\"headerVaultRefs\":{\"Authorization\":\"mcp-header-nested\"}}}");

        assertTrue(resolved.contains("Bearer nested"));
        assertFalse(resolved.contains("headerVaultRefs"));
    }

    @Test
    void resolveVaultReferencesWrapsAMalformedPayload() {
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> scrubber.resolveVaultReferences("user-1", "not-json"));

        assertEquals("Unable to resolve staged MCP authentication headers", thrown.getMessage());
    }

    @Test
    void resolveVaultReferencesRejectsAnInvalidHeaderReference() {
        // A blank reference is a wiring fault, not "nothing to resolve" — it must not be silently skipped.
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> scrubber.resolveVaultReferences("user-1", "{\"headerVaultRefs\":{\"Authorization\":\"   \"}}"));

        assertEquals("A staged MCP authentication header reference is invalid", thrown.getMessage());
    }

    // ---- validateConnections without a validator ----

    @Test
    void validateConnectionsReportsWhenTheValidatorIsUnavailable() {
        scrubber.validator = null;

        assertEquals(
                "OnboardingSelectionValidator is unavailable",
                scrubber.validateConnections(List.of("{\"connections\":[]}")));
    }

    // ---- connection api-key handling ----

    @Test
    void scrubConnectionApiKeysRemovesANonStringApiKey() {
        // A string-valued apiKey never reaches this method: scrubMapSecrets walks the connections list first
        // and vaults it. Only a non-string value survives that pass, and it is dropped rather than carried.
        List<String> scrubbed = scrubber.processAndScrubSecrets(
                "user-1", List.of("{\"connections\":[{\"provider\":\"openai\",\"apiKey\":{\"nested\":\"v\"}}]}"));

        assertFalse(scrubbed.get(0).contains("apiKey"));
    }

    @Test
    void scrubConnectionApiKeysLeavesAConnectionWithoutTheKeyUntouched() {
        List<String> input = List.of("{\"connections\":[{\"provider\":\"openai\"}]}");

        assertEquals(input, scrubber.processAndScrubSecrets("user-1", input));
    }

    // ---- mcp header maps ----

    @Test
    void scrubMcpHeadersLeavesAnAllBlankHeaderMapUnmodified() {
        List<String> input = List.of("{\"mcp\":{\"headers\":{\"Authorization\":\"   \"}}}");

        assertEquals(input, scrubber.processAndScrubSecrets("user-1", input));
    }

    @Test
    void scrubMcpHeaderMapMergesAnExistingHeaderReferenceMap() {
        List<String> scrubbed = scrubber.processAndScrubSecrets(
                "user-1",
                List.of("{\"mcp\":{\"servers\":[{\"headers\":{\"X-Live\":\"live-value\"},"
                        + "\"headerVaultRefs\":{\"X-Kept\":\"mcp-header-kept\"}}]}}"));

        String out = scrubbed.get(0);
        assertFalse(out.contains("live-value"));
        // The pre-existing reference survives the merge alongside the newly staged one.
        assertTrue(out.contains("X-Kept"));
        assertTrue(out.contains("mcp-header-kept"));
        verify(vault).store(startsWith("mcp-header-"), any(), eq("user-1"));
    }

    // ---- encodeSecretValue ----

    @Test
    void scrubMapSecretsSkipsValuesThatAreAlreadyVaultOrHeaderReferences() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("apiKey", "   ");
        payload.put("otherKey", "vault-key-existing");
        payload.put("thirdKey", "mcp-header-existing");

        assertTrue(scrubber.scrubMapSecrets(payload, "user-1"));

        verify(vault, never()).store(anyString(), any(), anyString());
        assertFalse(payload.containsKey("apiKey"));
    }

    @Test
    void scrubMapSecretsSkipsAnExplicitNullSecretValue() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("apiKey", null);

        assertTrue(scrubber.scrubMapSecrets(payload, "user-1"));

        verify(vault, never()).store(anyString(), any(), anyString());
        assertFalse(payload.containsKey("apiKey"));
    }

    @Test
    void scrubMapSecretsLeavesAnUnencodableValueUnvaulted() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("apiKey", new Object());

        // Jackson cannot serialise a bare Object, so there is nothing to vault — the key is still dropped.
        assertTrue(scrubber.scrubMapSecrets(payload, "user-1"));

        verify(vault, never()).store(anyString(), any(), anyString());
        assertFalse(payload.containsKey("apiKey"));
    }

    @Test
    void scrubMapSecretsReturnsFalseForANullMap() {
        assertFalse(scrubber.scrubMapSecrets(null, "user-1"));
    }

    // ---- empty / absent selection lists ----

    @Test
    void processAndScrubSecretsReturnsAnEmptySelectionListUnchanged() {
        assertEquals(List.of(), scrubber.processAndScrubSecrets("user-1", List.of()));
        assertNull(scrubber.processAndScrubSecrets("user-1", null));
        verifyNoInteractions(vault);
    }

    @Test
    void extractExtensionPayloadReturnsAnEmptyObjectWithoutSelections() {
        assertEquals("{}", scrubber.extractExtensionPayload(null, "mcp"));
        assertEquals("{}", scrubber.extractExtensionPayload(List.of(), "mcp"));
        assertEquals("{}", scrubber.extractExtensionPayload(List.of("{\"other\":1}"), "mcp"));
    }

    // ---- mcp header maps: absent and malformed shapes ----

    @Test
    void scrubMcpHeadersLeavesAnEmptyHeaderMapUnmodified() {
        List<String> input = List.of("{\"mcp\":{\"headers\":{}}}");

        assertEquals(input, scrubber.processAndScrubSecrets("user-1", input));
    }

    @Test
    void scrubMcpHeadersSkipsAnExplicitNullHeaderValue() {
        List<String> input = List.of("{\"mcp\":{\"headers\":{\"Authorization\":null}}}");

        assertEquals(input, scrubber.processAndScrubSecrets("user-1", input));
    }

    @Test
    void scrubMcpServerHeadersIgnoresANonObjectServerEntry() {
        List<String> input = List.of("{\"mcp\":{\"servers\":[\"not-a-map\"]}}");

        assertEquals(input, scrubber.processAndScrubSecrets("user-1", input));
    }

    @Test
    void scrubMcpHeaderMapRejectsANonMapHeaderContainer() {
        List<String> input = List.of("{\"mcp\":{\"servers\":[{\"headers\":\"not-a-map\"}]}}");

        assertEquals(input, scrubber.processAndScrubSecrets("user-1", input));
    }

    @Test
    void scrubMcpHeaderMapRejectsANonStringExistingReference() {
        List<String> scrubbed = scrubber.processAndScrubSecrets(
                "user-1",
                List.of("{\"mcp\":{\"servers\":[{\"headers\":{\"X-Live\":\"live-value\"},"
                        + "\"headerVaultRefs\":{\"A\":123}}]}}"));

        // The unusable pre-existing reference is dropped rather than carried into the rewritten map.
        assertFalse(scrubbed.get(0).contains("\"A\""));
        assertFalse(scrubbed.get(0).contains("live-value"));
    }

    @Test
    void scrubMcpHeaderMapSkipsNonStringAndBlankHeaderEntries() {
        List<String> scrubbed = scrubber.processAndScrubSecrets(
                "user-1", List.of("{\"mcp\":{\"servers\":[{\"headers\":{\"X\":123,\"Y\":\"\",\"Z\":\"ok\"}}]}}"));

        String out = scrubbed.get(0);
        assertFalse(out.contains("ok"));
        assertTrue(out.contains("\"Z\""));
    }

    @Test
    void scrubMcpHeaderMapLeavesAnAllBlankServerHeaderMapUnmodified() {
        List<String> input = List.of("{\"mcp\":{\"servers\":[{\"headers\":{\"X\":\"\"}}]}}");

        assertEquals(input, scrubber.processAndScrubSecrets("user-1", input));
    }

    // ---- resolveVaultReferences: absent and malformed shapes ----

    @Test
    void resolveVaultReferencesIgnoresANonObjectServerEntry() {
        String payload = "{\"servers\":[\"not-a-map\"]}";

        assertEquals(payload, scrubber.resolveVaultReferences("user-1", payload));
    }

    @Test
    void resolveVaultReferencesLeavesAnEmptyReferenceMapUnresolved() {
        String payload = "{\"headerVaultRefs\":{}}";

        assertEquals(payload, scrubber.resolveVaultReferences("user-1", payload));
    }

    @Test
    void resolveVaultReferencesRejectsANonStringReference() {
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> scrubber.resolveVaultReferences("user-1", "{\"headerVaultRefs\":{\"A\":123}}"));

        assertEquals("A staged MCP authentication header reference is invalid", thrown.getMessage());
    }

    // ---- cleanup: absent and malformed shapes ----

    @Test
    void cleanupStagedSecretsIgnoresANonObjectServerEntry() {
        scrubber.cleanupStagedSecrets(draftWithSelections(List.of("{\"mcp\":{\"servers\":[\"not-a-map\"]}}")));

        verifyNoInteractions(vault);
    }

    @Test
    void cleanupStagedSecretsIgnoresAServerWithoutHeaderReferences() {
        scrubber.cleanupStagedSecrets(draftWithSelections(List.of("{\"mcp\":{\"servers\":[{\"name\":\"x\"}]}}")));

        verifyNoInteractions(vault);
    }

    @Test
    void cleanupStagedSecretsIgnoresANonStringHeaderReference() {
        scrubber.cleanupStagedSecrets(
                draftWithSelections(List.of("{\"mcp\":{\"servers\":[{\"headerVaultRefs\":{\"A\":123}}]}}")));

        verifyNoInteractions(vault);
    }

    @Test
    void cleanupStagedSecretsToleratesANullConnectionEntry() {
        scrubber.cleanupStagedSecrets(draftWithSelections(List.of("{\"connections\":[null]}")));

        verifyNoInteractions(vault);
    }

    @Test
    void cleanupStagedSecretsSkipsAnEmptyNestedObject() {
        scrubber.cleanupStagedSecrets(draftWithSelections(List.of("{\"security\":{}}")));

        verifyNoInteractions(vault);
    }

    @Test
    void cleanupStagedSecretsIgnoresKeysThatAreNotStagedVaultReferences() {
        // "tokenRef" ends with Ref but does not hold a vault reference; "apiKey" ends with neither.
        scrubber.cleanupStagedSecrets(
                draftWithSelections(List.of("{\"security\":{\"tokenRef\":\"not-a-vault-ref\",\"apiKey\":\"plain\"}}")));

        verifyNoInteractions(vault);
    }

    // ---- isSecretKey ----

    @Test
    void isSecretKeyRejectsNullAndVaultReferenceSuffixes() {
        assertFalse(OnboardingSelectionScrubber.isSecretKey(null));
        // The suffix exclusion is case-insensitive, so a *VaultRef key is never treated as secret material.
        assertFalse(OnboardingSelectionScrubber.isSecretKey("myVaultRef"));
        assertFalse(OnboardingSelectionScrubber.isSecretKey("APIKEYVAULTREF"));
        assertTrue(OnboardingSelectionScrubber.isSecretKey("apiKey"));
        assertTrue(OnboardingSelectionScrubber.isSecretKey("refreshToken"));
    }
}
