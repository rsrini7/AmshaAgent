package com.amsha.core.tools;

import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Converts MCP JSON schemas to the LangChain4j schema model used by chat requests. */
public final class DynamicToolSchemaParser {
    private DynamicToolSchemaParser() {}

    public static JsonObjectSchema parse(ObjectMapper mapper, String inputSchema) {
        try {
            JsonNode root = mapper.readTree(inputSchema == null || inputSchema.isBlank() ? "{}" : inputSchema);
            if (!root.isObject()) {
                return JsonObjectSchema.builder().build();
            }
            JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
            if (root.has("description") && root.get("description").isTextual()) {
                builder.description(root.get("description").asText());
            }
            JsonNode properties = root.get("properties");
            if (properties != null && properties.isObject()) {
                properties
                        .properties()
                        .forEach(entry -> builder.addProperty(entry.getKey(), parseElement(entry.getValue())));
            }
            JsonNode required = root.get("required");
            if (required != null && required.isArray()) {
                List<String> names = new ArrayList<>();
                required.forEach(node -> {
                    if (node.isTextual()) {
                        names.add(node.asText());
                    }
                });
                builder.required(names);
            }
            return builder.build();
        } catch (Exception e) {
            return JsonObjectSchema.builder().build();
        }
    }

    private static JsonSchemaElement parseElement(JsonNode node) {
        String type = node.path("type").asText("string");
        String description = node.has("description") && node.get("description").isTextual()
                ? node.get("description").asText()
                : null;
        return switch (type) {
            case "integer" ->
                JsonIntegerSchema.builder().description(description).build();
            case "number" -> JsonNumberSchema.builder().description(description).build();
            case "boolean" ->
                JsonBooleanSchema.builder().description(description).build();
            case "array" ->
                JsonArraySchema.builder()
                        .items(node.has("items") ? parseElement(node.get("items")) : new JsonStringSchema())
                        .description(description)
                        .build();
            case "object" -> parseObject(node);
            default -> enumOrString(node, description);
        };
    }

    private static JsonSchemaElement parseObject(JsonNode node) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        if (node.has("description") && node.get("description").isTextual()) {
            builder.description(node.get("description").asText());
        }
        JsonNode properties = node.get("properties");
        if (properties != null && properties.isObject()) {
            properties
                    .properties()
                    .forEach(entry -> builder.addProperty(entry.getKey(), parseElement(entry.getValue())));
        }
        return builder.build();
    }

    private static JsonSchemaElement enumOrString(JsonNode node, String description) {
        JsonNode values = node.get("enum");
        if (values != null && values.isArray()) {
            List<String> enumValues = new ArrayList<>();
            values.forEach(value -> enumValues.add(value.asText()));
            return JsonEnumSchema.builder()
                    .enumValues(enumValues)
                    .description(description)
                    .build();
        }
        return JsonStringSchema.builder().description(description).build();
    }
}
