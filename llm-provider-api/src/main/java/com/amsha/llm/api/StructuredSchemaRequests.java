package com.amsha.llm.api;

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Request-scoped native JSON-Schema carriage for chat requests.
 *
 * <p>Attaching a schema constrains decoding at the provider level; it never
 * replaces the bounded self-correction loop, which stays authoritative. All
 * methods are fail-closed: an empty, unparsable, or unsupported schema throws
 * {@link IllegalArgumentException} and callers fall back to the prompt-directive
 * path.</p>
 *
 * <p>Supported schema constructs: typed objects with properties/required,
 * strings, enums, integers, numbers, booleans, arrays with items, and optional
 * descriptions. Unsupported constructs ({@code $ref}, {@code oneOf}/{@code
 * anyOf}/{@code allOf}, pattern properties, propertyless objects) are rejected
 * rather than approximated.</p>
 */
public final class StructuredSchemaRequests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StructuredSchemaRequests() {}

    /** True when the request carries a native JSON-Schema response format. */
    public static boolean hasNativeJsonSchema(ChatRequest request) {
        if (request == null
                || request.parameters() == null
                || request.parameters().responseFormat() == null) {
            return false;
        }
        ResponseFormat rf = request.parameters().responseFormat();
        return rf.type() == ResponseFormatType.JSON && rf.jsonSchema() != null;
    }

    /**
     * Returns a copy of the request carrying a native JSON-Schema response format
     * built from the given schema JSON. Provider-specific parameters are
     * preserved through {@link ChatRequestParameters#overrideWith}, which replaces
     * only the response format and keeps every other field — including fields
     * this API does not model — untouched.
     *
     * @throws IllegalArgumentException if the schema JSON is empty, unparsable,
     *         or uses unsupported constructs
     */
    public static ChatRequest withNativeJsonSchema(ChatRequest request, String schemaName, String schemaJson) {
        if (request == null) {
            throw new IllegalArgumentException("Chat request must not be null");
        }
        if (hasNativeJsonSchema(request)) {
            return request;
        }
        JsonSchema jsonSchema = parseSchema(schemaName, schemaJson);
        ChatRequestParameters schemaOnly = DefaultChatRequestParameters.builder()
                .responseFormat(jsonSchema)
                .build();
        ChatRequestParameters merged =
                request.parameters() == null ? schemaOnly : request.parameters().overrideWith(schemaOnly);
        return rebuild(request, merged);
    }

    /**
     * Returns a copy of the request with any native JSON-Schema response format
     * removed and every other base parameter preserved. The response format is
     * removed entirely rather than downgraded to generic JSON mode: prompt text
     * still instructs JSON output (the PROMPT_DIRECTIVE fallback), the bounded
     * self-correction loop stays authoritative, and providers that reject
     * schemaless JSON formats (e.g. Anthropic) are not tripped by a stale
     * response-format hint. Used at composite/failover boundaries before
     * forwarding to a candidate that cannot honor the schema.
     */
    public static ChatRequest withoutNativeJsonSchema(ChatRequest request) {
        if (!hasNativeJsonSchema(request)) {
            return request;
        }
        ChatRequestParameters cleared = request.parameters()
                .overrideWith(DefaultChatRequestParameters.builder()
                        .responseFormat(ResponseFormat.TEXT)
                        .build());
        return rebuild(request, cleared);
    }

    /**
     * Per-candidate forwarding decision for composite/failover wrappers: a
     * schema-carrying request is forwarded verbatim only to candidates whose
     * provider deliberately advertises strict-schema support; every other
     * candidate receives the rebuilt (format-free) request. Advertisement-only,
     * matching
     * {@link StructuredOutputCapabilities#resolvesToStrictSchema(StructuredOutputMode, boolean, String, boolean)}
     * — declarations are informative and
     * never an admission signal. The prompt text still describes the expected
     * output, so the prompt-directive fallback remains intact.
     */
    public static ChatRequest forCandidate(ChatRequest request, boolean candidateAdvertisesStrictSchema) {
        if (!hasNativeJsonSchema(request)) {
            return request;
        }
        return candidateAdvertisesStrictSchema ? request : withoutNativeJsonSchema(request);
    }

    /**
     * Parses a raw JSON-Schema document into a LangChain4j {@link ResponseFormat}.
     *
     * @throws IllegalArgumentException on empty/unparsable/unsupported schemas
     */
    public static ResponseFormat responseFormatFromSchema(String schemaName, String schemaJson) {
        return ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(parseSchema(schemaName, schemaJson))
                .build();
    }

    private static JsonSchema parseSchema(String schemaName, String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            throw new IllegalArgumentException("Empty JSON schema for '" + schemaName + "'");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(schemaJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unparsable JSON schema for '" + schemaName + "'", e);
        }
        JsonSchemaElement rootElement = elementFor(root, schemaName);
        if (!(rootElement instanceof JsonObjectSchema obj) || obj.properties().isEmpty()) {
            throw new IllegalArgumentException(
                    "Schema root for '" + schemaName + "' must be an object with at least one property");
        }
        // Fail-closed validation: required entries must reference declared
        // properties (objectFor already applied them; no rebuild needed here).
        for (String name : obj.required()) {
            if (!obj.properties().containsKey(name)) {
                throw new IllegalArgumentException(
                        "Required property '" + name + "' not declared at root of '" + schemaName + "'");
            }
        }
        return JsonSchema.builder().name(schemaName).rootElement(obj).build();
    }

    private static JsonSchemaElement elementFor(JsonNode node, String path) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("Unsupported non-object schema node at '" + path + "'");
        }
        rejectUnsupportedKeywords(node, path);
        JsonNode typeNode = node.get("type");
        if (typeNode != null && !typeNode.isTextual()) {
            throw new IllegalArgumentException("Schema 'type' at '" + path + "' must be a string");
        }
        String type = textOrNull(typeNode);
        JsonNode enumNode = node.get("enum");
        if (enumNode != null && !enumNode.isArray()) {
            throw new IllegalArgumentException("Schema 'enum' at '" + path + "' must be an array");
        }
        if (enumNode != null) {
            List<String> values = new ArrayList<>();
            enumNode.forEach(v -> {
                if (!v.isTextual()) {
                    throw new IllegalArgumentException("Non-textual enum value at '" + path + "' is not supported");
                }
                values.add(v.asText());
            });
            JsonEnumSchema.Builder b = JsonEnumSchema.builder().enumValues(values);
            if (node.has("description")) b.description(node.get("description").asText());
            return b.build();
        }
        if (type == null) {
            throw new IllegalArgumentException("Missing schema 'type' at '" + path + "'");
        }
        JsonNode descriptionNode = node.get("description");
        if (descriptionNode != null && !descriptionNode.isTextual()) {
            throw new IllegalArgumentException("Schema 'description' at '" + path + "' must be a string");
        }
        String description = descriptionNode == null ? null : descriptionNode.asText();
        return switch (type) {
            case "object" -> objectFor(node, path);
            case "array" -> {
                JsonNode items = node.get("items");
                if (items == null) {
                    throw new IllegalArgumentException("Array schema at '" + path + "' has no items");
                }
                JsonArraySchema.Builder ab = JsonArraySchema.builder().items(elementFor(items, path + "[]"));
                if (description != null) ab.description(description);
                yield ab.build();
            }
            case "string" -> {
                JsonStringSchema.Builder sb = JsonStringSchema.builder();
                if (description != null) sb.description(description);
                yield sb.build();
            }
            case "integer" -> {
                JsonIntegerSchema.Builder ib = JsonIntegerSchema.builder();
                if (description != null) ib.description(description);
                yield ib.build();
            }
            case "number" -> {
                JsonNumberSchema.Builder nb = JsonNumberSchema.builder();
                if (description != null) nb.description(description);
                yield nb.build();
            }
            case "boolean" -> {
                JsonBooleanSchema.Builder bb = JsonBooleanSchema.builder();
                if (description != null) bb.description(description);
                yield bb.build();
            }
            default ->
                throw new IllegalArgumentException("Unsupported schema type at '" + path + "' (type=" + type + ")");
        };
    }

    private static JsonObjectSchema objectFor(JsonNode node, String path) {
        JsonNode props = node.get("properties");
        if (props == null || !props.isObject() || props.isEmpty()) {
            throw new IllegalArgumentException("Object schema at '" + path + "' has no properties");
        }
        Map<String, JsonSchemaElement> properties = new LinkedHashMap<>();
        props.properties().forEach(e -> {
            String name = e.getKey();
            properties.put(name, elementFor(e.getValue(), path + "." + name));
        });
        JsonObjectSchema.Builder b = JsonObjectSchema.builder().addProperties(properties);
        List<String> required = stringList(node.get("required"));
        if (!required.isEmpty()) {
            b.required(required);
        }
        JsonNode additionalProperties = node.get("additionalProperties");
        if (additionalProperties != null) {
            if (!additionalProperties.isBoolean()) {
                throw new IllegalArgumentException("Schema 'additionalProperties' at '" + path + "' must be a boolean");
            }
            b.additionalProperties(additionalProperties.booleanValue());
        }
        if (node.has("description")) {
            b.description(node.get("description").asText());
        }
        for (String name : required) {
            if (!properties.containsKey(name)) {
                throw new IllegalArgumentException("Required property '" + name + "' not declared at '" + path + "'");
            }
        }
        return b.build();
    }

    private static void rejectUnsupportedKeywords(JsonNode node, String path) {
        for (String keyword : new String[] {"$ref", "oneOf", "anyOf", "allOf", "not", "patternProperties"}) {
            if (node.has(keyword)) {
                throw new IllegalArgumentException("Unsupported schema keyword '" + keyword + "' at '" + path + "'");
            }
        }
    }

    private static List<String> stringList(JsonNode arrayNode) {
        List<String> values = new ArrayList<>();
        if (arrayNode == null) {
            return values;
        }
        if (!arrayNode.isArray()) {
            throw new IllegalArgumentException("Schema 'required' must be an array");
        }
        arrayNode.forEach(v -> {
            if (!v.isTextual()) {
                throw new IllegalArgumentException("Every schema 'required' entry must be a string");
            }
            values.add(v.asText());
        });
        return values;
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.textValue();
    }

    private static ChatRequest rebuild(ChatRequest request, ChatRequestParameters params) {
        return ChatRequest.builder()
                .messages(request.messages())
                .parameters(params)
                .build();
    }
}
