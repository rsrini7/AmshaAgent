package com.arcclaw.core.structured;

import java.util.regex.Pattern;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Lenient JSON parser that strips markdown fences and handles common LLM quirks
 * (trailing commas, single quotes, unquoted field names).
 */
@ApplicationScoped
public class PermissiveJsonExtractor {
    private static final Logger log = Logger.getLogger(PermissiveJsonExtractor.class);
    
    private static final int MAX_JSON_CHARS = 512 * 1024; // 512 KiB hard cap

    // Pattern to match ```json ... ``` or plain ``` ... ``` blocks
    private static final Pattern JSON_FENCE_PATTERN = Pattern.compile(
            "^\\s*```(?:json)?\\s*(.*?)\\s*```\\s*$", Pattern.DOTALL);

    public PermissiveJsonExtractor() {
        // No-op constructor, mapper built per-request to satisfy prompt requirements
    }

    private ObjectMapper buildPermissiveMapper() {
        return JsonMapper.builder()
                .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
                .enable(JsonParser.Feature.ALLOW_SINGLE_QUOTES)
                .enable(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                // We intentionally do NOT enable FAIL_ON_MISSING_CREATOR_PROPERTIES
                // to allow records with @Nullable fields (like SynthesisResult.conflicts)
                // to be instantiated even if the field is missing from the JSON.
                .build();
    }

    /**
     * Extracts an object of the given type from a raw string response.
     * 
     * @param rawResponse The raw string (potentially containing markdown fences)
     * @param targetType The class to deserialize into
     * @return The deserialized object
     * @throws JsonExtractionException If parsing fails despite leniency
     */
    public <T> T extract(String rawResponse, Class<T> targetType) {
        // Pre-parse validations — before any ObjectMapper construction
        if (rawResponse == null || rawResponse.isBlank()) {
            throw new JsonExtractionException("LLM returned null or blank response");
        }
        if (rawResponse.length() > MAX_JSON_CHARS) {
            throw new JsonExtractionException(
                String.format("LLM JSON response exceeds %d char limit (actual: %d chars). " +
                    "Possible prompt-stuffing or runaway generation.",
                    MAX_JSON_CHARS, rawResponse.length()));
        }

        ObjectMapper mapper = buildPermissiveMapper();
        // Add duplicate key detection — duplicate keys in LLM output = schema confusion
        mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

        String cleaned = stripFences(rawResponse);
        cleaned = unwrapSchemaEnvelope(cleaned, mapper);
        try {
            return mapper.readValue(cleaned, targetType);
        } catch (Exception e) {
            log.warnf("JSON extraction failed for type %s. Cleaned input: %s", targetType.getSimpleName(), cleaned);
            throw new JsonExtractionException(
                    "Permissive extraction failed: " + e.getMessage(),
                    rawResponse, targetType, cleaned, e);
        }
    }

    /**
     * Unwraps a JSON Schema envelope that the LLM sometimes echoes back
     * instead of returning a flat instance.
     *
     * LLM incorrectly returns: {"type":"object","properties":{"contributions":[...],"finalResolution":"..."}}
     * This method extracts the "properties" value, giving Jackson a flat instance to deserialize.
     */
    private String unwrapSchemaEnvelope(String json, ObjectMapper mapper) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root.has("type") && root.has("properties")
                    && "object".equals(root.get("type").asText())) {
                return mapper.writeValueAsString(root.get("properties"));
            }
        } catch (Exception ignored) {
            // Not a schema envelope — return original
        }
        return json;
    }
    
    private String stripFences(String input) {
        var matcher = JSON_FENCE_PATTERN.matcher(input);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return input.trim();
    }

}
