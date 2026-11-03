package com.amsha.core.tool.attempt;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Generates deterministic action fingerprints for tool invocations per Proposal §4.1 and §12.2.
 * <p>
 * The fingerprint is formatted as {@code sha256:v1:<lowercase-sha256-hex>} over an RFC 8785 canonical
 * JSON envelope containing {@code workspaceId}, {@code sessionId}, {@code toolName}, and normalized
 * {@code parameters}.
 * <p>
 * Non-JSON parameter types fail closed. Integral values preserve their parsed numeric representation.
 * Raw parameters and fingerprints are never leaked in error messages or logs.
 */
@ApplicationScoped
public class ActionFingerprintGenerator {

    public static final String FINGERPRINT_VERSION = "v1";
    public static final String PREFIX = "sha256:" + FINGERPRINT_VERSION + ":";

    private final ObjectMapper objectMapper;

    public ActionFingerprintGenerator() {
        this(new ObjectMapper());
    }

    @Inject
    public ActionFingerprintGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    /**
     * Generates the versioned action fingerprint for a tool call with Map parameters.
     *
     * @param workspaceId non-blank workspace identity
     * @param sessionId non-blank session identity
     * @param toolName non-blank canonical tool name
     * @param parameters tool parameters map, or null
     * @return versioned fingerprint formatted as {@code sha256:v1:<hex>}
     */
    public String generate(String workspaceId, String sessionId, String toolName, Map<String, Object> parameters) {
        return compute(workspaceId, sessionId, toolName, parameters, this.objectMapper);
    }

    /**
     * Generates the versioned action fingerprint for a tool call with JsonNode parameters.
     *
     * @param workspaceId non-blank workspace identity
     * @param sessionId non-blank session identity
     * @param toolName non-blank canonical tool name
     * @param parameters tool parameters JSON node, or null
     * @return versioned fingerprint formatted as {@code sha256:v1:<hex>}
     */
    public String generate(String workspaceId, String sessionId, String toolName, JsonNode parameters) {
        return compute(workspaceId, sessionId, toolName, parameters, this.objectMapper);
    }

    /**
     * Produces the RFC 8785 canonical UTF-8 bytes for a tool call envelope.
     */
    public byte[] canonicalBytes(
            String workspaceId, String sessionId, String toolName, Map<String, Object> parameters) {
        return buildCanonicalBytes(workspaceId, sessionId, toolName, parameters, this.objectMapper);
    }

    /**
     * Produces the RFC 8785 canonical UTF-8 bytes for a tool call envelope.
     */
    public byte[] canonicalBytes(String workspaceId, String sessionId, String toolName, JsonNode parameters) {
        return buildCanonicalBytes(workspaceId, sessionId, toolName, parameters, this.objectMapper);
    }

    /**
     * Produces the RFC 8785 canonical JSON string for a tool call envelope.
     */
    public String canonicalJson(String workspaceId, String sessionId, String toolName, Map<String, Object> parameters) {
        return buildCanonicalJson(workspaceId, sessionId, toolName, parameters, this.objectMapper);
    }

    /**
     * Produces the RFC 8785 canonical JSON string for a tool call envelope.
     */
    public String canonicalJson(String workspaceId, String sessionId, String toolName, JsonNode parameters) {
        return buildCanonicalJson(workspaceId, sessionId, toolName, parameters, this.objectMapper);
    }

    // Static convenience helpers

    public static String computeFingerprint(
            String workspaceId, String sessionId, String toolName, Map<String, Object> parameters) {
        return compute(workspaceId, sessionId, toolName, parameters, null);
    }

    public static String computeFingerprint(
            String workspaceId, String sessionId, String toolName, JsonNode parameters) {
        return compute(workspaceId, sessionId, toolName, parameters, null);
    }

    public static byte[] computeCanonicalBytes(
            String workspaceId, String sessionId, String toolName, Map<String, Object> parameters) {
        return buildCanonicalBytes(workspaceId, sessionId, toolName, parameters, null);
    }

    public static byte[] computeCanonicalBytes(
            String workspaceId, String sessionId, String toolName, JsonNode parameters) {
        return buildCanonicalBytes(workspaceId, sessionId, toolName, parameters, null);
    }

    public static String computeCanonicalJson(
            String workspaceId, String sessionId, String toolName, Map<String, Object> parameters) {
        return buildCanonicalJson(workspaceId, sessionId, toolName, parameters, null);
    }

    public static String computeCanonicalJson(
            String workspaceId, String sessionId, String toolName, JsonNode parameters) {
        return buildCanonicalJson(workspaceId, sessionId, toolName, parameters, null);
    }

    private static ObjectNode buildEnvelope(
            String workspaceId, String sessionId, String toolName, Object rawParameters) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId must not be null or blank");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be null or blank");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be null or blank");
        }
        JsonNode normalizedParams = CanonicalJsonUtil.normalize(rawParameters);

        ObjectNode envelope = JsonNodeFactory.instance.objectNode();
        envelope.put("workspaceId", workspaceId);
        envelope.put("sessionId", sessionId);
        envelope.put("toolName", toolName);
        envelope.set("parameters", normalizedParams);
        return envelope;
    }

    private static byte[] buildCanonicalBytes(
            String workspaceId, String sessionId, String toolName, Object rawParameters, ObjectMapper mapper) {
        ObjectNode envelope = buildEnvelope(workspaceId, sessionId, toolName, rawParameters);
        return CanonicalJsonUtil.canonicalizeNodeToUtf8(envelope, mapper);
    }

    private static String buildCanonicalJson(
            String workspaceId, String sessionId, String toolName, Object rawParameters, ObjectMapper mapper) {
        ObjectNode envelope = buildEnvelope(workspaceId, sessionId, toolName, rawParameters);
        return CanonicalJsonUtil.canonicalizeNode(envelope, mapper);
    }

    private static String compute(
            String workspaceId, String sessionId, String toolName, Object rawParameters, ObjectMapper mapper) {
        byte[] bytes = buildCanonicalBytes(workspaceId, sessionId, toolName, rawParameters, mapper);
        return PREFIX + CanonicalJsonUtil.sha256Hex(bytes);
    }
}
