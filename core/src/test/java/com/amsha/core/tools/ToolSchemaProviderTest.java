package com.amsha.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNullSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.List;
import java.util.Map;

import com.amsha.tool.api.AgentTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the schema-to-map / schema-to-JSON translation.
 *
 * <p>These are pure functions over LangChain4j schema objects, so they are exercised directly with no
 * Quarkus container. The mapping is the contract the model sees when a tool's parameter schema is
 * advertised, which makes the omitted-vs-present distinctions (empty {@code properties},
 * blank {@code required}, null {@code description}) worth pinning precisely.
 */
class ToolSchemaProviderTest {

    private ToolSchemaProvider provider;

    @BeforeEach
    void setUp() {
        provider = new ToolSchemaProvider();
        provider.objectMapper = new ObjectMapper();
    }

    // ================= schemaToMap =================

    @Test
    void nullSchemaMapsToNull() {
        assertNull(ToolSchemaProvider.schemaToMap(null));
    }

    @Test
    void bareObjectSchemaCarriesOnlyTheType() {
        Map<String, Object> result =
                ToolSchemaProvider.schemaToMap(JsonObjectSchema.builder().build());

        // No empty "properties"/"required" keys, and no null "description".
        assertEquals(Map.of("type", "object"), result);
    }

    @Test
    void propertiesAndRequiredListAreMapped() {
        JsonObjectSchema schema = JsonObjectSchema.builder()
                .addStringProperty("city")
                .addIntegerProperty("days")
                .required("city")
                .build();

        Map<String, Object> result = ToolSchemaProvider.schemaToMap(schema);

        assertEquals("object", result.get("type"));
        assertEquals(List.of("city"), result.get("required"));
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) result.get("properties");
        assertEquals(Map.of("type", "string"), props.get("city"));
        assertEquals(Map.of("type", "integer"), props.get("days"));
    }

    @Test
    void blankRequiredListIsOmitted() {
        Map<String, Object> result = ToolSchemaProvider.schemaToMap(JsonObjectSchema.builder()
                .addStringProperty("a")
                .required(List.of())
                .build());

        assertFalse(result.containsKey("required"), "an empty required list must not be emitted");
        assertTrue(result.containsKey("properties"));
    }

    @Test
    void objectDescriptionIsCarriedThrough() {
        Map<String, Object> result = ToolSchemaProvider.schemaToMap(
                JsonObjectSchema.builder().description("top level").build());

        assertEquals("top level", result.get("description"));
    }

    @Test
    void nestedObjectSchemaIsMappedRecursively() {
        JsonObjectSchema schema = JsonObjectSchema.builder()
                .addProperty(
                        "nested",
                        JsonObjectSchema.builder().addStringProperty("inner").build())
                .build();

        @SuppressWarnings("unchecked")
        Map<String, Object> props =
                (Map<String, Object>) ToolSchemaProvider.schemaToMap(schema).get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) props.get("nested");

        assertEquals("object", nested.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> inner = (Map<String, Object>) nested.get("properties");
        assertEquals(Map.of("type", "string"), inner.get("inner"));
    }

    // ================= elementToMap =================

    @Test
    void nullElementMapsToNull() {
        assertNull(ToolSchemaProvider.elementToMap(null));
    }

    @Test
    void scalarSchemasMapToTheirJsonType() {
        assertEquals(
                Map.of("type", "string"),
                ToolSchemaProvider.elementToMap(JsonStringSchema.builder().build()));
        assertEquals(
                Map.of("type", "integer"),
                ToolSchemaProvider.elementToMap(JsonIntegerSchema.builder().build()));
        assertEquals(
                Map.of("type", "boolean"),
                ToolSchemaProvider.elementToMap(JsonBooleanSchema.builder().build()));
    }

    @Test
    void scalarDescriptionsAreIncludedWhenPresent() {
        assertEquals(
                Map.of("type", "string", "description", "a name"),
                ToolSchemaProvider.elementToMap(
                        JsonStringSchema.builder().description("a name").build()));
        assertEquals(
                Map.of("type", "integer", "description", "how many"),
                ToolSchemaProvider.elementToMap(
                        JsonIntegerSchema.builder().description("how many").build()));
        assertEquals(
                Map.of("type", "boolean", "description", "on/off"),
                ToolSchemaProvider.elementToMap(
                        JsonBooleanSchema.builder().description("on/off").build()));
    }

    @Test
    void arraySchemaMapsItsItemType() {
        assertEquals(
                Map.of("type", "array", "items", Map.of("type", "string")),
                ToolSchemaProvider.elementToMap(JsonArraySchema.builder()
                        .items(JsonStringSchema.builder().build())
                        .build()));
    }

    @Test
    void arraySchemaWithoutItemsStillDeclaresTheArrayType() {
        Map<String, Object> result =
                asMap(ToolSchemaProvider.elementToMap(JsonArraySchema.builder().build()));

        assertEquals("array", result.get("type"));
        assertFalse(result.containsKey("items"), "a null item schema must not emit an 'items' key");
    }

    @Test
    void enumSchemaIsAdvertisedAsAStringWithItsValues() {
        assertEquals(
                Map.of("type", "string", "enum", List.of("fast", "slow")),
                ToolSchemaProvider.elementToMap(
                        JsonEnumSchema.builder().enumValues("fast", "slow").build()));
    }

    @Test
    void enumDescriptionIsIncludedWhenPresent() {
        assertEquals(
                Map.of("type", "string", "enum", List.of("a"), "description", "pick one"),
                ToolSchemaProvider.elementToMap(JsonEnumSchema.builder()
                        .enumValues("a")
                        .description("pick one")
                        .build()));
    }

    @Test
    void anyOfSchemaMapsEveryBranch() {
        assertEquals(
                Map.of("anyOf", List.of(Map.of("type", "string"), Map.of("type", "integer"))),
                ToolSchemaProvider.elementToMap(JsonAnyOfSchema.builder()
                        .anyOf(
                                JsonStringSchema.builder().build(),
                                JsonIntegerSchema.builder().build())
                        .build()));
    }

    @Test
    void anyOfDescriptionIsIncludedWhenPresent() {
        assertEquals(
                Map.of("anyOf", List.of(Map.of("type", "string")), "description", "either"),
                ToolSchemaProvider.elementToMap(JsonAnyOfSchema.builder()
                        .anyOf(JsonStringSchema.builder().build())
                        .description("either")
                        .build()));
    }

    /** {@code elementToMap} is typed as {@code Object}; this narrows it for map-shaped assertions. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object element) {
        return (Map<String, Object>) element;
    }

    @Test
    void schemasWithNoRepresentationFallBackToAny() {
        // JsonNullSchema is a JsonSchemaElement with no dedicated arm, so it hits the default branch.
        // Emitting "any" keeps the schema valid rather than dropping the property.
        assertEquals(Map.of("type", "any"), ToolSchemaProvider.elementToMap(new JsonNullSchema()));
    }

    // ================= getJsonSchema =================

    @Test
    void getJsonSchemaSerializesTheToolSchema() throws Exception {
        AgentTool tool = mock(AgentTool.class);
        when(tool.parametersSchema())
                .thenReturn(JsonObjectSchema.builder()
                        .addStringProperty("city")
                        .required("city")
                        .build());

        String json = provider.getJsonSchema(tool);

        JsonNode node = new ObjectMapper().readTree(json);
        assertEquals("object", node.get("type").asText());
        assertEquals("string", node.get("properties").get("city").get("type").asText());
        assertEquals("city", node.get("required").get(0).asText());
    }

    @Test
    void getJsonSchemaReturnsNullWhenTheToolDeclaresNoSchema() {
        AgentTool tool = mock(AgentTool.class);
        when(tool.parametersSchema()).thenReturn(null);

        assertNull(provider.getJsonSchema(tool));
    }

    @Test
    void getJsonSchemaReturnsNullRatherThanPropagatingFailures() {
        // A tool whose schema accessor blows up must not take the caller down with it.
        AgentTool tool = mock(AgentTool.class);
        when(tool.name()).thenReturn("broken_tool");
        when(tool.parametersSchema()).thenThrow(new IllegalStateException("schema exploded"));

        assertNull(provider.getJsonSchema(tool));
    }
}
