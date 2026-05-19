package com.arcclaw.core.tools;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.common.util.LogUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import org.jboss.logging.Logger;

/**
 * Generates and serializes JSON schemas for tool parameters.
 */
@ApplicationScoped
public class ToolSchemaProvider {

    private static final Logger log = Logger.getLogger(ToolSchemaProvider.class);

    @Inject ObjectMapper objectMapper;

    public String getJsonSchema(AgentTool tool) {
        try {
            var ps = tool.parametersSchema();
            var schemaMap = schemaToMap(ps);
            return schemaMap != null
                ? objectMapper.writeValueAsString(schemaMap)
                : null;
        } catch (Exception e) {
            log.warnf("Could not serialize schema for tool %s: %s",
                tool.name(), LogUtil.rootMessage(e));
            return null;
        }
    }

    private Map<String, Object> schemaToMap(JsonObjectSchema schema) {
        if (schema == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "object");
        if (schema.properties() != null && !schema.properties().isEmpty()) {
            Map<String, Object> props = new LinkedHashMap<>();
            schema.properties().forEach((k, v) -> props.put(k, elementToMap(v)));
            result.put("properties", props);
        }
        if (schema.required() != null && !schema.required().isEmpty()) {
            result.put("required", schema.required());
        }
        if (schema.description() != null) {
            result.put("description", schema.description());
        }
        return result;
    }

    private Object elementToMap(JsonSchemaElement el) {
        if (el == null) return null;
        return switch (el) {
            case JsonObjectSchema o -> schemaToMap(o);
            case JsonStringSchema s -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("type", "string");
                if (s.description() != null) m.put("description", s.description());
                yield m;
            }
            case JsonIntegerSchema i -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("type", "integer");
                if (i.description() != null) m.put("description", i.description());
                yield m;
            }
            case JsonBooleanSchema b -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("type", "boolean");
                if (b.description() != null) m.put("description", b.description());
                yield m;
            }
            case JsonArraySchema a -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("type", "array");
                if (a.items() != null) m.put("items", elementToMap(a.items()));
                if (a.description() != null) m.put("description", a.description());
                yield m;
            }
            case JsonEnumSchema e -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("type", "string");
                m.put("enum", e.enumValues());
                if (e.description() != null) m.put("description", e.description());
                yield m;
            }
            case JsonAnyOfSchema a -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("anyOf", a.anyOf().stream()
                        .map(this::elementToMap)
                        .toList());
                if (a.description() != null) m.put("description", a.description());
                yield m;
            }
            default -> Map.of("type", "any");
        };
    }
}
