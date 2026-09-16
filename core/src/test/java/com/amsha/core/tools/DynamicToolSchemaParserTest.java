package com.amsha.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DynamicToolSchemaParser}.
 *
 * <p>The parser is deliberately <em>total</em>: every malformed-input path degrades to an empty
 * {@link JsonObjectSchema} instead of throwing. That matters because this sits on the path that turns an
 * MCP server's advertised tool schema into a chat request — a throw here would abort tool registration
 * for the entire server. "Never throws, always returns a usable schema" is therefore the primary
 * contract under test, and the null/blank/malformed cases are not padding.
 */
class DynamicToolSchemaParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonSchemaElement property(JsonObjectSchema schema, String name) {
        JsonSchemaElement element = schema.properties().get(name);
        assertNotNull(element, "expected property '" + name + "' to be present");
        return element;
    }

    private JsonObjectSchema parse(String json) {
        return DynamicToolSchemaParser.parse(mapper, json);
    }

    // ---------- total-function guarantees ----------

    @Test
    void parseNullInputReturnsEmptySchema() {
        JsonObjectSchema schema = parse(null);

        assertTrue(schema.properties().isEmpty(), "null input must not invent properties");
        assertNull(schema.description());
    }

    @Test
    void parseBlankInputReturnsEmptySchema() {
        assertTrue(parse("   ").properties().isEmpty());
    }

    @Test
    void parseEmptyObjectReturnsEmptySchema() {
        JsonObjectSchema schema = parse("{}");

        assertTrue(schema.properties().isEmpty());
        assertTrue(schema.required().isEmpty());
        assertNull(schema.description());
    }

    @Test
    void parseMalformedJsonReturnsEmptySchemaInsteadOfThrowing() {
        // Each of these would throw from ObjectMapper.readTree; the parser must swallow it.
        assertTrue(parse("{not valid json").properties().isEmpty());
        assertTrue(parse("{\"properties\": }").properties().isEmpty());
        assertTrue(parse("<<<>>>").properties().isEmpty());
    }

    @Test
    void parseNonObjectRootReturnsEmptySchema() {
        // A JSON document whose root is an array, number, or string is not a valid tool schema.
        assertTrue(parse("[1, 2, 3]").properties().isEmpty());
        assertTrue(parse("42").properties().isEmpty());
        assertTrue(parse("\"just a string\"").properties().isEmpty());
        assertTrue(parse("true").properties().isEmpty());
        assertTrue(parse("null").properties().isEmpty());
    }

    // ---------- top-level object ----------

    @Test
    void parseTopLevelDescriptionIsPropagated() {
        assertEquals(
                "Does a thing", parse("{\"description\": \"Does a thing\"}").description());
    }

    @Test
    void parseNonTextualTopLevelDescriptionIsIgnored() {
        assertNull(parse("{\"description\": 123}").description());
    }

    @Test
    void parsePropertiesNotAnObjectIsIgnored() {
        assertTrue(parse("{\"properties\": \"not-an-object\"}").properties().isEmpty());
        assertTrue(parse("{\"properties\": [1, 2]}").properties().isEmpty());
        assertTrue(parse("{\"properties\": null}").properties().isEmpty());
    }

    @Test
    void parseRequiredListIsCaptured() {
        JsonObjectSchema schema = parse("{\"required\": [\"alpha\", \"beta\"]}");

        assertEquals(List.of("alpha", "beta"), schema.required());
    }

    @Test
    void parseRequiredSkipsNonTextualEntries() {
        // Only the textual element should survive; numbers/booleans/objects are dropped, not coerced.
        assertEquals(
                List.of("alpha"),
                parse("{\"required\": [\"alpha\", 7, true, {\"k\": 1}]}").required());
    }

    @Test
    void parseRequiredNotAnArrayIsIgnored() {
        assertTrue(parse("{\"required\": \"alpha\"}").required().isEmpty());
    }

    // ---------- scalar type mapping ----------

    @Test
    void parseStringPropertyMapsToJsonStringSchema() {
        JsonObjectSchema schema = parse("{\"properties\": {\"name\": {\"type\": \"string\"}}}");

        assertInstanceOf(JsonStringSchema.class, property(schema, "name"));
    }

    @Test
    void parseMissingTypeDefaultsToJsonStringSchema() {
        // `type` is optional in the MCP/JSON-Schema dialect the parser accepts.
        JsonObjectSchema schema = parse("{\"properties\": {\"name\": {\"description\": \"no type\"}}}");

        JsonSchemaElement element = property(schema, "name");
        assertInstanceOf(JsonStringSchema.class, element);
        assertEquals("no type", element.description());
    }

    @Test
    void parseUnknownTypeFallsBackToJsonStringSchema() {
        assertInstanceOf(
                JsonStringSchema.class, property(parse("{\"properties\": {\"x\": {\"type\": \"quaternion\"}}}"), "x"));
    }

    @Test
    void parseIntegerPropertyMapsToJsonIntegerSchema() {
        assertInstanceOf(
                JsonIntegerSchema.class, property(parse("{\"properties\": {\"n\": {\"type\": \"integer\"}}}"), "n"));
    }

    @Test
    void parseNumberPropertyMapsToJsonNumberSchema() {
        assertInstanceOf(
                JsonNumberSchema.class, property(parse("{\"properties\": {\"n\": {\"type\": \"number\"}}}"), "n"));
    }

    @Test
    void parseBooleanPropertyMapsToJsonBooleanSchema() {
        assertInstanceOf(
                JsonBooleanSchema.class, property(parse("{\"properties\": {\"b\": {\"type\": \"boolean\"}}}"), "b"));
    }

    @Test
    void parsePropertyDescriptionIsPropagated() {
        JsonObjectSchema schema =
                parse("{\"properties\": {\"name\": {\"type\": \"string\", \"description\": \"the name\"}}}");

        assertEquals("the name", property(schema, "name").description());
    }

    @Test
    void parseNonTextualPropertyDescriptionIsIgnored() {
        JsonObjectSchema schema = parse("{\"properties\": {\"name\": {\"type\": \"string\", \"description\": 5}}}");

        assertNull(property(schema, "name").description());
    }

    // ---------- arrays ----------

    @Test
    void parseArrayPropertyWithItemsUsesItemSchema() {
        JsonObjectSchema schema =
                parse("{\"properties\": {\"nums\": {\"type\": \"array\", \"items\": {\"type\": \"integer\"}}}}");

        JsonArraySchema array = assertInstanceOf(JsonArraySchema.class, property(schema, "nums"));
        assertInstanceOf(JsonIntegerSchema.class, array.items());
    }

    @Test
    void parseArrayPropertyWithoutItemsDefaultsToStringItems() {
        JsonObjectSchema schema = parse("{\"properties\": {\"tags\": {\"type\": \"array\"}}}");

        JsonArraySchema array = assertInstanceOf(JsonArraySchema.class, property(schema, "tags"));
        assertInstanceOf(JsonStringSchema.class, array.items());
    }

    @Test
    void parseArrayPropertyCarriesDescription() {
        JsonObjectSchema schema =
                parse("{\"properties\": {\"tags\": {\"type\": \"array\", \"description\": \"labels\"}}}");

        assertEquals("labels", property(schema, "tags").description());
    }

    @Test
    void parseArrayOfObjectsRecurses() {
        String json =
                "{\"properties\": {\"rows\": {\"type\": \"array\", \"items\": {\"type\": \"object\", \"properties\": {\"id\": {\"type\": \"integer\"}}}}}}";

        JsonArraySchema array = assertInstanceOf(JsonArraySchema.class, property(parse(json), "rows"));
        JsonObjectSchema item = assertInstanceOf(JsonObjectSchema.class, array.items());
        assertInstanceOf(JsonIntegerSchema.class, item.properties().get("id"));
    }

    // ---------- nested objects ----------

    @Test
    void parseObjectPropertyMapsToNestedJsonObjectSchema() {
        String json =
                "{\"properties\": {\"cfg\": {\"type\": \"object\", \"properties\": {\"depth\": {\"type\": \"integer\"}}}}}";

        JsonObjectSchema nested = assertInstanceOf(JsonObjectSchema.class, property(parse(json), "cfg"));
        assertInstanceOf(JsonIntegerSchema.class, nested.properties().get("depth"));
    }

    @Test
    void parseNestedObjectDescriptionIsPropagated() {
        String json = "{\"properties\": {\"cfg\": {\"type\": \"object\", \"description\": \"config\"}}}";

        assertEquals("config", property(parse(json), "cfg").description());
    }

    @Test
    void parseObjectPropertyWithoutPropertiesIsEmpty() {
        String json = "{\"properties\": {\"cfg\": {\"type\": \"object\"}}}";

        JsonObjectSchema nested = assertInstanceOf(JsonObjectSchema.class, property(parse(json), "cfg"));
        assertTrue(nested.properties().isEmpty());
    }

    @Test
    void parseRecursesThroughSeveralLevelsOfNesting() {
        String json = """
                {"properties": {"outer": {"type": "object", "properties": {
                  "middle": {"type": "object", "properties": {
                    "inner": {"type": "boolean"}}}}}}}
                """;

        JsonObjectSchema outer = assertInstanceOf(JsonObjectSchema.class, property(parse(json), "outer"));
        JsonObjectSchema middle =
                assertInstanceOf(JsonObjectSchema.class, outer.properties().get("middle"));
        assertInstanceOf(JsonBooleanSchema.class, middle.properties().get("inner"));
    }

    // ---------- enums ----------

    @Test
    void parseEnumPropertyMapsToJsonEnumSchema() {
        JsonObjectSchema schema = parse("{\"properties\": {\"mode\": {\"enum\": [\"fast\", \"slow\"]}}}");

        JsonEnumSchema enumSchema = assertInstanceOf(JsonEnumSchema.class, property(schema, "mode"));
        assertEquals(List.of("fast", "slow"), enumSchema.enumValues());
    }

    @Test
    void parseEnumCarriesDescription() {
        JsonObjectSchema schema =
                parse("{\"properties\": {\"mode\": {\"enum\": [\"a\"], \"description\": \"pick one\"}}}");

        assertEquals("pick one", property(schema, "mode").description());
    }

    @Test
    void parseEnumWithNonTextualValuesCoercesToText() {
        // JSON-Schema allows numeric/boolean enum members; they are stringified rather than dropped.
        JsonObjectSchema schema = parse("{\"properties\": {\"level\": {\"enum\": [1, 2, true]}}}");

        JsonEnumSchema enumSchema = assertInstanceOf(JsonEnumSchema.class, property(schema, "level"));
        assertEquals(List.of("1", "2", "true"), enumSchema.enumValues());
    }

    @Test
    void parseEnumWithEmptyValuesDiscardsTheEntireSchema() {
        // KNOWN SHARP EDGE, pinned here deliberately.
        //
        // `enum: []` reaches enumOrString(), which builds a JsonEnumSchema. That constructor calls
        // ValidationUtils.ensureNotEmpty(enumValues, "enumValues") and THROWS on an empty list. The
        // throw propagates out of the property loop into parse()'s catch-all, which returns an empty
        // JsonObjectSchema - so a single malformed property discards EVERY sibling property too.
        //
        // An empty enum is not valid JSON Schema, so the input is malformed; the concern is the blast
        // radius, not the rejection. Documented rather than "fixed" because changing it alters
        // production behaviour on the MCP tool-registration path.
        assertTrue(parse("{\"properties\": {\"mode\": {\"enum\": []}}}")
                .properties()
                .isEmpty());
    }

    @Test
    void parseOneMalformedEnumDiscardsAllSiblingProperties() {
        // The blast radius of the above, stated explicitly: `good` is perfectly valid but is lost.
        String json = "{\"properties\": {\"good\": {\"type\": \"string\"}, \"bad\": {\"enum\": []}}}";

        JsonObjectSchema schema = parse(json);

        assertTrue(schema.properties().isEmpty(), "the valid sibling property is discarded too");
        assertNull(schema.properties().get("good"));
    }

    @Test
    void parseEnumThatIsNotAnArrayFallsBackToStringSchema() {
        assertInstanceOf(
                JsonStringSchema.class,
                property(parse("{\"properties\": {\"mode\": {\"enum\": \"single\"}}}"), "mode"));
    }

    @Test
    void parseExplicitStringTypeWithEnumPrefersEnum() {
        // `enum` is honoured even when `type: string` is present, since the default branch handles it.
        JsonObjectSchema schema = parse("{\"properties\": {\"mode\": {\"type\": \"string\", \"enum\": [\"a\"]}}}");

        assertInstanceOf(JsonEnumSchema.class, property(schema, "mode"));
    }

    // ---------- malformed property nodes ----------

    @Test
    void parsePropertyThatIsNotAnObjectDegradesToStringSchema() {
        // e.g. `{"properties": {"x": "string"}}` — a shorthand some servers emit.
        JsonObjectSchema schema = parse("{\"properties\": {\"x\": \"string\"}}");

        assertInstanceOf(JsonStringSchema.class, property(schema, "x"));
    }

    @Test
    void parsePropertyThatIsNullIsStillRegistered() {
        JsonObjectSchema schema = parse("{\"properties\": {\"x\": null}}");

        assertInstanceOf(JsonStringSchema.class, property(schema, "x"));
    }

    // ---------- a realistic MCP-shaped schema ----------

    @Test
    void parseRealisticMcpSchemaProducesEveryElementKind() {
        String json = """
                {
                  "description": "Search the knowledge base",
                  "properties": {
                    "query":    {"type": "string", "description": "free text"},
                    "topK":     {"type": "integer"},
                    "threshold":{"type": "number"},
                    "exact":    {"type": "boolean"},
                    "tags":     {"type": "array", "items": {"type": "string"}},
                    "filter":   {"type": "object", "properties": {"field": {"type": "string"}}},
                    "sort":     {"enum": ["asc", "desc"]}
                  },
                  "required": ["query"]
                }
                """;

        JsonObjectSchema schema = parse(json);

        assertEquals("Search the knowledge base", schema.description());
        assertEquals(List.of("query"), schema.required());
        assertEquals(7, schema.properties().size(), "every advertised property must be registered");
        assertInstanceOf(JsonStringSchema.class, property(schema, "query"));
        assertInstanceOf(JsonIntegerSchema.class, property(schema, "topK"));
        assertInstanceOf(JsonNumberSchema.class, property(schema, "threshold"));
        assertInstanceOf(JsonBooleanSchema.class, property(schema, "exact"));
        assertInstanceOf(JsonArraySchema.class, property(schema, "tags"));
        assertInstanceOf(JsonObjectSchema.class, property(schema, "filter"));
        assertInstanceOf(JsonEnumSchema.class, property(schema, "sort"));
    }

    @Test
    void parseDoesNotMutateOrRetainTheInputMapperState() {
        // Two calls with the same mapper must be independent (the parser is stateless).
        JsonObjectSchema first = parse("{\"properties\": {\"a\": {\"type\": \"integer\"}}}");
        JsonObjectSchema second = parse("{\"properties\": {\"b\": {\"type\": \"string\"}}}");

        assertEquals(1, first.properties().size());
        assertEquals(1, second.properties().size());
        assertInstanceOf(JsonIntegerSchema.class, first.properties().get("a"));
        assertInstanceOf(JsonStringSchema.class, second.properties().get("b"));
    }
}
