package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.amsha.persistence.api.ModelProfileDto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Focused unit tests for {@link ModelProfileValidator}:
 * - valid profiles
 * - missing model IDs
 * - invalid MIME/type pairs
 * - malformed capability JSON
 * - invalid limits
 * - unsupported VIDEO
 * - two profiles sharing one connection (N:1 sharing)
 * - forbidden fallbackOrder
 * - unknown connection references
 */
class ModelProfileValidatorTest {

    private ModelProfileValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ModelProfileValidator();
    }

    @Test
    void validTextProfile_passesValidation() {
        Instant now = Instant.now();
        ModelProfileDto profile = new ModelProfileDto(
                "prof-text-1", "conn-1", "llama-3.3-70b-versatile", "NATIVE", null, null, true, 1, now, now);

        ProfileValidationResult result = validator.validateProfile(profile);

        assertTrue(result.valid());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void validMultimodalVisionProfile_passesValidation() {
        Instant now = Instant.now();
        String capabilityJson = """
                {
                  "supportedTypes": ["IMAGE"],
                  "supportedMimeTypes": ["image/png", "image/jpeg", "image/webp"],
                  "maxFileSize": 10485760,
                  "maxMediaPerRequest": 5
                }
                """;
        ModelProfileDto profile = new ModelProfileDto(
                "prof-vision-1",
                "conn-1",
                "Qwen3.8-27B-4bit",
                "PROMPT_DIRECTIVE",
                capabilityJson,
                "{\"maxFileSize\": 10485760}",
                true,
                1,
                now,
                now);

        ProfileValidationResult result = validator.validateProfile(profile);

        assertTrue(result.valid());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void validMultimodalAudioAndPdfProfile_passesValidation() {
        String capabilityJson = """
                {
                  "supportedTypes": ["AUDIO", "DOCUMENT_PDF"],
                  "supportedMimeTypes": ["audio/wav", "audio/mp3; codecs=opus", "application/pdf"],
                  "maxFileSize": 20971520,
                  "maxMediaPerRequest": 2
                }
                """;
        Map<String, Object> draft = Map.of(
                "id", "prof-audio-pdf-1",
                "connectionId", "conn-multi",
                "modelId", "gemini-1.5-flash",
                "structuredOutputMode", "LENIENT",
                "capabilityJson", capabilityJson);

        ProfileValidationResult result = validator.validateProfileDraft(draft);

        assertTrue(result.valid());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void missingModelId_failsValidation() {
        Map<String, Object> draft = Map.of(
                "id", "prof-1",
                "connectionId", "conn-1",
                "modelId", "   ");

        ProfileValidationResult result = validator.validateProfileDraft(draft);

        assertFalse(result.valid());
        assertTrue(result.errorSummary().contains("modelId is required"));
    }

    @Test
    void missingConnectionId_failsValidation() {
        Map<String, Object> draft = Map.of(
                "id", "prof-1",
                "modelId", "gpt-4o");

        ProfileValidationResult result = validator.validateProfileDraft(draft);

        assertFalse(result.valid());
        assertTrue(result.errorSummary().contains("connectionId is required"));
    }

    @Test
    void missingId_failsValidation() {
        Map<String, Object> draft = Map.of(
                "connectionId", "conn-1",
                "modelId", "gpt-4o");

        ProfileValidationResult result = validator.validateProfileDraft(draft);

        assertFalse(result.valid());
        assertTrue(result.errorSummary().contains("id is required"));
    }

    @Test
    void invalidStructuredOutputMode_failsValidation() {
        Map<String, Object> draft = Map.of(
                "id", "prof-1",
                "connectionId", "conn-1",
                "modelId", "gpt-4o",
                "structuredOutputMode", "BOGUS_MODE");

        ProfileValidationResult result = validator.validateProfileDraft(draft);

        assertFalse(result.valid());
        assertTrue(result.errorSummary().contains("Invalid structuredOutputMode: BOGUS_MODE"));
    }

    @Test
    void malformedCapabilityJson_failsValidation() {
        Map<String, Object> draft = Map.of(
                "id", "prof-1",
                "connectionId", "conn-1",
                "modelId", "gpt-4o",
                "capabilityJson", "not-a-json-object{");

        ProfileValidationResult result = validator.validateProfileDraft(draft);

        assertFalse(result.valid());
        assertTrue(result.errorSummary().contains("capabilityJson must be valid JSON object"));
    }

    @Test
    void unsupportedVideoModality_failsValidation() {
        String capabilityJson = """
                {
                  "supportedTypes": ["IMAGE", "VIDEO"],
                  "supportedMimeTypes": ["image/png"]
                }
                """;
        ProfileValidationResult result = validator.validateCapabilityJson(capabilityJson);

        assertFalse(result.valid());
        assertTrue(result.errorSummary().contains("VIDEO is not supported"));
    }

    @Test
    void unsupportedVideoMimeType_failsValidation() {
        String capabilityJson = """
                {
                  "supportedTypes": ["IMAGE"],
                  "supportedMimeTypes": ["video/mp4"]
                }
                """;
        ProfileValidationResult result = validator.validateCapabilityJson(capabilityJson);

        assertFalse(result.valid());
        assertTrue(result.errorSummary().contains("video MIME types are not supported"));
    }

    @Test
    void unknownAttachmentType_failsValidation() {
        String capabilityJson = """
                {
                  "supportedTypes": ["HOLOGRAM"]
                }
                """;
        ProfileValidationResult result = validator.validateCapabilityJson(capabilityJson);

        assertFalse(result.valid());
        assertTrue(result.errorSummary().contains("Unknown or unsupported attachment type: HOLOGRAM"));
    }

    @Test
    void invalidMimeTypeFormat_failsValidation() {
        String capabilityJson = """
                {
                  "supportedTypes": ["IMAGE"],
                  "supportedMimeTypes": ["not-a-mime"]
                }
                """;
        ProfileValidationResult result = validator.validateCapabilityJson(capabilityJson);

        assertFalse(result.valid());
        assertTrue(result.errorSummary().contains("Invalid MIME type format: not-a-mime"));
    }

    @Test
    void invalidMimeTypePairs_missingDeclaredModality_failsValidation() {
        // Declares AUDIO only, but specifies image/png
        String capabilityJson = """
                {
                  "supportedTypes": ["AUDIO"],
                  "supportedMimeTypes": ["image/png", "audio/wav"]
                }
                """;
        ProfileValidationResult result = validator.validateCapabilityJson(capabilityJson);

        assertFalse(result.valid());
        assertTrue(result.errorSummary().contains("incompatible with declared supportedTypes (missing IMAGE)"));
    }

    @Test
    void invalidMimeTypePairs_emptySupportedTypesWithMimes_failsValidation() {
        String capabilityJson = """
                {
                  "supportedTypes": [],
                  "supportedMimeTypes": ["application/pdf"]
                }
                """;
        ProfileValidationResult result = validator.validateCapabilityJson(capabilityJson);

        assertFalse(result.valid());
        assertTrue(result.errorSummary().contains("missing DOCUMENT_PDF"));
    }

    @Test
    void negativeMaxFileSize_failsValidation() {
        String capabilityJson = """
                {
                  "supportedTypes": ["IMAGE"],
                  "maxFileSize": -500
                }
                """;
        ProfileValidationResult result = validator.validateCapabilityJson(capabilityJson);

        assertFalse(result.valid());
        assertTrue(result.errorSummary().contains("maxFileSize cannot be negative"));
    }

    @Test
    void negativeMaxMediaPerRequest_failsValidation() {
        String capabilityJson = """
                {
                  "supportedTypes": ["IMAGE"],
                  "maxMediaPerRequest": -1
                }
                """;
        ProfileValidationResult result = validator.validateCapabilityJson(capabilityJson);

        assertFalse(result.valid());
        assertTrue(result.errorSummary().contains("maxMediaPerRequest cannot be negative"));
    }

    @Test
    void malformedSafeLimitsJson_failsValidation() {
        ProfileValidationResult result = validator.validateSafeLimitsJson("not-json{");

        assertFalse(result.valid());
        assertTrue(result.errorSummary().contains("safeLimitsJson must be valid JSON object"));
    }

    @Test
    void negativeSafeLimits_failsValidation() {
        String safeLimits = "{\"maxOutputTokens\": -100, \"timeoutMs\": -1}";
        ProfileValidationResult result = validator.validateSafeLimitsJson(safeLimits);

        assertFalse(result.valid());
        assertTrue(result.errorSummary().contains("cannot be negative"));
    }

    @Test
    void forbiddenFallbackOrder_failsValidation() {
        Map<String, Object> draft = Map.of(
                "id", "prof-1",
                "connectionId", "conn-1",
                "modelId", "gpt-4o",
                "fallbackOrder", 1);

        ProfileValidationResult result = validator.validateProfileDraft(draft);

        assertFalse(result.valid());
        assertTrue(result.errorSummary().contains("fallbackOrder is not permitted on model profiles"));
    }

    @Test
    void topology_twoProfilesSharingOneConnection_passesValidation() {
        // N:1 connection sharing test
        List<Map<String, Object>> connections =
                List.of(Map.of("id", "conn-shared", "provider", "omlx", "baseUrl", "http://localhost:8000"));

        List<Map<String, Object>> profiles = List.of(
                Map.of(
                        "id", "prof-text",
                        "connectionId", "conn-shared",
                        "modelId", "Ornith-1.5-35B-A3B-MLX-4bit",
                        "structuredOutputMode", "PROMPT_DIRECTIVE"),
                Map.of(
                        "id", "prof-vision",
                        "connectionId", "conn-shared",
                        "modelId", "Qwen3.8-27B-4bit",
                        "structuredOutputMode", "NATIVE",
                        "capabilityJson", "{\"supportedTypes\": [\"IMAGE\"]}"));

        ProfileValidationResult result = validator.validateDraftTopology(connections, profiles, Set.of());

        assertTrue(result.valid(), "N:1 sharing of conn-shared by prof-text and prof-vision must succeed");
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void topology_duplicateProfileId_failsValidation() {
        List<Map<String, Object>> connections = List.of(Map.of("id", "conn-1", "provider", "openai"));

        List<Map<String, Object>> profiles = List.of(
                Map.of("id", "prof-dupe", "connectionId", "conn-1", "modelId", "model-a"),
                Map.of("id", "prof-dupe", "connectionId", "conn-1", "modelId", "model-b"));

        ProfileValidationResult result = validator.validateDraftTopology(connections, profiles, Set.of());

        assertFalse(result.valid());
        assertTrue(result.errorSummary().contains("Duplicate profile ID across drafts: prof-dupe"));
    }

    @Test
    void topology_duplicateConnectionId_failsValidation() {
        List<Map<String, Object>> connections = List.of(
                Map.of("id", "conn-dupe", "provider", "openai"), Map.of("id", "conn-dupe", "provider", "anthropic"));

        ProfileValidationResult result = validator.validateDraftTopology(connections, List.of(), Set.of());

        assertFalse(result.valid());
        assertTrue(result.errorSummary().contains("Duplicate connection ID across drafts: conn-dupe"));
    }

    @Test
    void topology_nullAndNonMapEntries_handledGracefully() {
        ArrayList<Object> connections = new ArrayList<>();
        connections.add(null);
        connections.add("not a map");

        ArrayList<Object> profiles = new ArrayList<>();
        profiles.add(null);
        profiles.add(123);

        ProfileValidationResult result = validator.validateDraftTopology(connections, profiles, Set.of());

        assertFalse(result.valid());
        assertTrue(result.errorSummary().contains("Connection draft entry must be a non-null JSON object"));
        assertTrue(result.errorSummary().contains("Profile draft entry must be a non-null JSON object"));
    }

    @Test
    void validateProfileDraft_wrongFieldTypes_rejected() {
        Map<String, Object> draft = Map.of(
                "id", "prof-types",
                "connectionId", "conn-1",
                "modelId", "gpt-4o",
                "structuredOutputMode", 123,
                "capabilityJson", List.of("bad"),
                "safeLimitsJson", true,
                "enabled", "not-a-boolean");

        ProfileValidationResult result = validator.validateProfileDraft(draft);

        assertFalse(result.valid());
        assertTrue(result.errorSummary().contains("structuredOutputMode must be a string"));
        assertTrue(result.errorSummary().contains("capabilityJson must be a JSON string or JSON object"));
        assertTrue(result.errorSummary().contains("safeLimitsJson must be a JSON string or JSON object"));
        assertTrue(result.errorSummary().contains("enabled must be a boolean"));
    }

    @Test
    void validateConnectionDraft_networkPolicyValidation() {
        // Require proxy without proxy URL
        Map<String, Object> conn1 = Map.of(
                "id", "c1",
                "provider", "openai",
                "networkProfileJson", "{\"requireProxy\":true}");
        ProfileValidationResult res1 = validator.validateConnectionDraft(conn1);
        assertFalse(res1.valid());
        assertTrue(res1.errorSummary().contains("proxyUrl"));

        // Invalid proxy URL (not absolute http/https URI)
        Map<String, Object> conn2 = Map.of(
                "id", "c2",
                "provider", "openai",
                "networkProfileJson", "{\"proxyUrl\":\"ftp://not-http\"}");
        ProfileValidationResult res2 = validator.validateConnectionDraft(conn2);
        assertFalse(res2.valid());
        assertTrue(res2.errorSummary().contains("proxyUrl"));

        // ALLOW_LIST without hosts
        Map<String, Object> conn3 = Map.of(
                "id", "c3",
                "provider", "openai",
                "networkProfileJson", "{\"egressMode\":\"ALLOW_LIST\",\"allowedHosts\":[]}");
        ProfileValidationResult res3 = validator.validateConnectionDraft(conn3);
        assertFalse(res3.valid());
        assertTrue(res3.errorSummary().contains("allowedHosts"));

        // CUSTOM_CA without vault ref
        Map<String, Object> conn4 = Map.of(
                "id", "c4",
                "provider", "openai",
                "networkProfileJson", "{\"tlsTrustMode\":\"CUSTOM_CA\"}");
        ProfileValidationResult res4 = validator.validateConnectionDraft(conn4);
        assertFalse(res4.valid());
        assertTrue(res4.errorSummary().contains("customCaVaultRef"));

        // Non-string networkProfileJson
        Map<String, Object> conn5 = Map.of(
                "id", "c5",
                "provider", "openai",
                "networkProfileJson", 12345);
        ProfileValidationResult res5 = validator.validateConnectionDraft(conn5);
        assertFalse(res5.valid());
        assertTrue(res5.errorSummary().contains("networkProfileJson must be a JSON string or JSON object"));

        // Zero or negative timeout
        Map<String, Object> conn6 = Map.of(
                "id", "c6",
                "provider", "openai",
                "networkProfileJson", "{\"connectTimeoutMs\":0}");
        ProfileValidationResult res6 = validator.validateConnectionDraft(conn6);
        assertFalse(res6.valid());
        assertTrue(res6.errorSummary().contains("positive"));
    }

    @Test
    void topology_unknownConnectionId_failsValidation() {
        List<Map<String, Object>> connections = List.of(Map.of("id", "conn-1", "provider", "openai"));

        List<Map<String, Object>> profiles =
                List.of(Map.of("id", "prof-1", "connectionId", "conn-unknown", "modelId", "model-a"));

        ProfileValidationResult result = validator.validateDraftTopology(connections, profiles, Set.of());

        assertFalse(result.valid());
        assertTrue(result.errorSummary().contains("references unknown connectionId: conn-unknown"));
    }

    @Test
    void topology_referencesExistingStoreConnection_passesValidation() {
        List<Map<String, Object>> profiles =
                List.of(Map.of("id", "prof-1", "connectionId", "conn-existing-in-db", "modelId", "model-a"));

        ProfileValidationResult result =
                validator.validateDraftTopology(List.of(), profiles, Set.of("conn-existing-in-db"));

        assertTrue(result.valid());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void validateNullInputs_returnFailResults() {
        ProfileValidationResult pRes = validator.validateProfile(null);
        assertFalse(pRes.valid());
        assertTrue(pRes.errorSummary().contains("Model profile must not be null"));

        ProfileValidationResult dRes = validator.validateProfileDraft(null);
        assertFalse(dRes.valid());
        assertTrue(dRes.errorSummary().contains("Profile draft must not be null"));

        ProfileValidationResult cRes = validator.validateConnectionDraft(null);
        assertFalse(cRes.valid());
        assertTrue(cRes.errorSummary().contains("Connection draft must not be null"));
    }

    @Test
    void profileValidationResult_crossFieldValidation_enforcesConsistency() {
        assertThrows(
                IllegalArgumentException.class, () -> new ProfileValidationResult(true, List.of("error occurred")));

        ProfileValidationResult res1 = ProfileValidationResult.fail("single error");
        assertFalse(res1.valid());
        assertEquals(List.of("single error"), res1.errors());

        ProfileValidationResult resNull = ProfileValidationResult.fail((String) null);
        assertFalse(resNull.valid());
        assertTrue(resNull.errors().isEmpty());
    }

    @Test
    void validateProfileDraft_mapValuedCapabilityAndLimits_handled() {
        Map<String, Object> draft = Map.of(
                "id", "prof-map",
                "connectionId", "conn-1",
                "modelId", "gpt-4o",
                "capabilityJson", Map.of("maxFileSize", 1024),
                "safeLimitsJson", Map.of("timeoutMs", 5000));

        ProfileValidationResult result = validator.validateProfileDraft(draft);
        assertTrue(result.valid());

        // Map with un-encodable object
        Map<String, Object> unencodableDraft = Map.of(
                "id", "prof-bad-map",
                "connectionId", "conn-1",
                "modelId", "gpt-4o",
                "capabilityJson", Map.of("unserializable", new Object()));

        ProfileValidationResult badResult = validator.validateProfileDraft(unencodableDraft);
        assertFalse(badResult.valid());
        assertTrue(badResult.errorSummary().contains("capabilityJson must be a JSON string or JSON object"));
    }

    @Test
    void validateCapabilityJson_twoPassAttachmentOrderAndOtherType() {
        // Entry 0 declares image/png mimeType; entry 1 declares IMAGE type. Two-pass resolves without error.
        String twoPassJson =
                "{" + "\"attachments\":[" + "  {\"mimeTypes\":[\"image/png\"]}," + "  {\"type\":\"IMAGE\"}" + "]}";
        ProfileValidationResult result = validator.validateCapabilityJson(twoPassJson);
        assertTrue(result.valid());

        // Attachment type OTHER produces clear unsupported message
        String otherTypeJson = "{\"attachments\":[{\"type\":\"OTHER\"}]}";
        ProfileValidationResult otherResult = validator.validateCapabilityJson(otherTypeJson);
        assertFalse(otherResult.valid());
        assertTrue(otherResult.errorSummary().contains("Attachment type 'OTHER' is not supported"));
    }
}
