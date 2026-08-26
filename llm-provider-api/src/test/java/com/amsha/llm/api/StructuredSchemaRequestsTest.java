package com.amsha.llm.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class StructuredSchemaRequestsTest {

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "summary": {"type": "string", "description": "The summary"},
                "score": {"type": "integer"},
                "ratio": {"type": "number"},
                "ok": {"type": "boolean"},
                "tags": {"type": "array", "items": {"type": "string"}},
                "role": {"enum": ["coder", "debugger"]}
              },
              "required": ["summary"]
            }
            """;

    private static ChatRequest bare() {
        return ChatRequest.builder().messages(List.of(UserMessage.from("hi"))).build();
    }

    @Test
    void attachesNativeSchema_toBareRequest() {
        ChatRequest request = StructuredSchemaRequests.withNativeJsonSchema(bare(), "test-op", SCHEMA);

        assertTrue(StructuredSchemaRequests.hasNativeJsonSchema(request));
        ResponseFormat rf = request.parameters().responseFormat();
        assertEquals(ResponseFormatType.JSON, rf.type());
        assertNotNull(rf.jsonSchema());
        // messages preserved
        assertEquals(1, request.messages().size());
    }

    @Test
    void attach_isIdempotent_andPreservesOtherParameters() {
        ChatRequest withParams = ChatRequest.builder()
                .messages(List.of(UserMessage.from("hi")))
                .modelName("gpt-test")
                .temperature(0.2)
                .build();
        ChatRequest once = StructuredSchemaRequests.withNativeJsonSchema(withParams, "op", SCHEMA);
        assertEquals("gpt-test", once.parameters().modelName());
        assertEquals(0.2, once.parameters().temperature());

        ChatRequest twice = StructuredSchemaRequests.withNativeJsonSchema(once, "op", SCHEMA);
        assertSame(once, twice, "already carrying a native schema must be a no-op");
    }

    @Test
    void strip_removesOnlyResponseFormat_andIsNoOpWhenAbsent() {
        ChatRequest attached = StructuredSchemaRequests.withNativeJsonSchema(bare(), "op", SCHEMA);
        ChatRequest stripped = StructuredSchemaRequests.withoutNativeJsonSchema(attached);

        assertFalse(StructuredSchemaRequests.hasNativeJsonSchema(stripped));
        // TEXT is LangChain4j's format-free sentinel. Provider serializers omit it,
        // while overrideWith keeps provider-specific request parameters intact.
        assertEquals(
                ResponseFormatType.TEXT, stripped.parameters().responseFormat().type());
        assertEquals(1, stripped.messages().size());

        ChatRequest bare = bare();
        assertSame(bare, StructuredSchemaRequests.withoutNativeJsonSchema(bare));
    }

    @Test
    void forCandidate_forwardsOnlyToAdvertisedCandidates() {
        ChatRequest schemaRequest = StructuredSchemaRequests.withNativeJsonSchema(bare(), "op", SCHEMA);
        assertTrue(
                StructuredSchemaRequests.hasNativeJsonSchema(
                        StructuredSchemaRequests.forCandidate(schemaRequest, true)),
                "advertised candidate receives the schema verbatim");
        assertFalse(
                StructuredSchemaRequests.hasNativeJsonSchema(
                        StructuredSchemaRequests.forCandidate(schemaRequest, false)),
                "unadvertised candidate receives the rebuilt request even if a model declares");
    }

    @Test
    void emptyOrPropertylessSchemas_areRejected_failClosed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> StructuredSchemaRequests.withNativeJsonSchema(bare(), "op", "{}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> StructuredSchemaRequests.withNativeJsonSchema(bare(), "op", "  "));
        assertThrows(
                IllegalArgumentException.class,
                () -> StructuredSchemaRequests.withNativeJsonSchema(
                        bare(), "op", "{\"type\":\"object\",\"properties\":{}}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> StructuredSchemaRequests.withNativeJsonSchema(
                        bare(), "op", "{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"#/x\"}}}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> StructuredSchemaRequests.withNativeJsonSchema(bare(), "op", "not json at all"));
    }

    @Test
    void responseFormatFromSchema_exposesTypedProperties() {
        var rf = StructuredSchemaRequests.responseFormatFromSchema("op", SCHEMA);
        assertNotNull(rf.jsonSchema());
        var root = (JsonObjectSchema) rf.jsonSchema().rootElement();
        Map<String, JsonSchemaElement> props = root.properties();
        assertTrue(props.containsKey("summary"));
        assertTrue(root.required().contains("summary"));
        var tags = (JsonArraySchema) props.get("tags");
        assertTrue(tags.items() instanceof JsonStringSchema);
    }

    @Test
    void descriptions_preservedOnPrimitivesAndObjects() {
        var rf = StructuredSchemaRequests.responseFormatFromSchema("op", SCHEMA);
        var root = (JsonObjectSchema) rf.jsonSchema().rootElement();

        var summary = (JsonStringSchema) root.properties().get("summary");
        assertEquals("The summary", summary.description());

        var withObjectDescription = StructuredSchemaRequests.responseFormatFromSchema(
                "op",
                "{\"type\":\"object\",\"description\":\"Root doc\",\"properties\":{\"a\":{\"type\":\"string\"}}}");
        var objRoot = (JsonObjectSchema) withObjectDescription.jsonSchema().rootElement();
        assertEquals("Root doc", objRoot.description());
    }

    @Test
    void preservesProviderSpecificParameters_whenAttachingAndStrippingSchema() {
        ProviderParameters parameters = new ProviderParameters("vendor-value", ResponseFormat.JSON);
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(UserMessage.from("hi")))
                .parameters(parameters)
                .build();

        ChatRequest attached = StructuredSchemaRequests.withNativeJsonSchema(request, "op", SCHEMA);
        assertTrue(attached.parameters() instanceof ProviderParameters);
        assertEquals("vendor-value", ((ProviderParameters) attached.parameters()).vendorValue());
        assertTrue(StructuredSchemaRequests.hasNativeJsonSchema(attached));

        ChatRequest stripped = StructuredSchemaRequests.withoutNativeJsonSchema(attached);
        assertTrue(stripped.parameters() instanceof ProviderParameters);
        assertEquals("vendor-value", ((ProviderParameters) stripped.parameters()).vendorValue());
        assertEquals(
                ResponseFormatType.TEXT, stripped.parameters().responseFormat().type());
    }

    @Test
    void retainsAdditionalPropertiesAndValidatesNestedRequiredNames() {
        String schema = """
                {
                  "type": "object",
                  "additionalProperties": false,
                  "properties": {
                    "child": {
                      "type": "object",
                      "additionalProperties": false,
                      "properties": {"value": {"type": "string"}},
                      "required": ["value"]
                    }
                  },
                  "required": ["child"]
                }
                """;

        JsonObjectSchema root = (JsonObjectSchema) StructuredSchemaRequests.responseFormatFromSchema("nested", schema)
                .jsonSchema()
                .rootElement();
        assertFalse(root.additionalProperties());
        JsonObjectSchema child = (JsonObjectSchema) root.properties().get("child");
        assertFalse(child.additionalProperties());

        String invalidNestedRequired = schema.replace("[\"value\"]", "[\"missing\"]");
        assertThrows(
                IllegalArgumentException.class,
                () -> StructuredSchemaRequests.responseFormatFromSchema("nested", invalidNestedRequired));
    }

    @Test
    void rejectsMalformedRequiredValues_insteadOfCoercingThem() {
        String nonArray = """
                {"type":"object","properties":{"answer":{"type":"string"}},"required":"answer"}
                """;
        String nonString = """
                {"type":"object","properties":{"answer":{"type":"string"}},"required":[1]}
                """;

        assertThrows(
                IllegalArgumentException.class,
                () -> StructuredSchemaRequests.responseFormatFromSchema("non-array", nonArray));
        assertThrows(
                IllegalArgumentException.class,
                () -> StructuredSchemaRequests.responseFormatFromSchema("non-string", nonString));
    }

    @Test
    void hasNativeJsonSchema_handlesNullGracefully() {
        assertFalse(StructuredSchemaRequests.hasNativeJsonSchema(null));
        assertFalse(StructuredSchemaRequests.hasNativeJsonSchema(bare()));
    }

    @Test
    void rejectsInvalidSchemaNodeTypes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> StructuredSchemaRequests.responseFormatFromSchema(
                        "op", "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":123}}}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> StructuredSchemaRequests.responseFormatFromSchema(
                        "op", "{\"type\":\"object\",\"properties\":{\"a\":{\"enum\":\"value\"}}}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> StructuredSchemaRequests.responseFormatFromSchema(
                        "op", "{\"type\":\"object\",\"properties\":{\"a\":{\"enum\":[123]}}}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> StructuredSchemaRequests.responseFormatFromSchema(
                        "op",
                        "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\",\"description\":123}}}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> StructuredSchemaRequests.responseFormatFromSchema(
                        "op", "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"array\"}}}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> StructuredSchemaRequests.responseFormatFromSchema(
                        "op",
                        "{\"type\":\"object\",\"additionalProperties\":\"false\",\"properties\":{\"a\":{\"type\":\"string\"}}}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> StructuredSchemaRequests.responseFormatFromSchema(
                        "op", "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"unsupported\"}}}"));
    }

    @Test
    void withNativeJsonSchema_returnsSameRequestIfAlreadyAttached() {
        ChatRequest req1 = StructuredSchemaRequests.withNativeJsonSchema(bare(), "op", SCHEMA);
        ChatRequest req2 = StructuredSchemaRequests.withNativeJsonSchema(req1, "op", SCHEMA);
        assertSame(req1, req2);
    }

    @Test
    void withNativeJsonSchema_throwsWhenRequestNull() {
        assertThrows(
                IllegalArgumentException.class,
                () -> StructuredSchemaRequests.withNativeJsonSchema(null, "op", SCHEMA));
    }

    @Test
    void withoutNativeJsonSchema_returnsSameRequestIfNotAttached() {
        ChatRequest bare = bare();
        ChatRequest result = StructuredSchemaRequests.withoutNativeJsonSchema(bare);
        assertSame(bare, result);
    }

    @Test
    void forCandidate_returnsSameRequestIfNotAttached() {
        ChatRequest bare = bare();
        assertSame(bare, StructuredSchemaRequests.forCandidate(bare, true));
        assertSame(bare, StructuredSchemaRequests.forCandidate(bare, false));
    }

    @Test
    void handlesEnumsWithAndWithoutDescriptions() {
        String schemaWithEnum = """
                {
                  "type": "object",
                  "properties": {
                    "role": {"enum": ["admin", "user"], "description": "User role"}
                  }
                }
                """;
        ResponseFormat rf = StructuredSchemaRequests.responseFormatFromSchema("enum-test", schemaWithEnum);
        assertNotNull(rf);
    }

    @Test
    void handlesAllPrimitiveTypesWithDescriptions() {
        String fullSchema = """
                {
                  "type": "object",
                  "description": "Root object",
                  "additionalProperties": false,
                  "properties": {
                    "s": {"type": "string", "description": "string desc"},
                    "i": {"type": "integer", "description": "int desc"},
                    "n": {"type": "number", "description": "num desc"},
                    "b": {"type": "boolean", "description": "bool desc"},
                    "a": {"type": "array", "description": "arr desc", "items": {"type": "string"}}
                  }
                }
                """;
        ResponseFormat rf = StructuredSchemaRequests.responseFormatFromSchema("full-test", fullSchema);
        assertNotNull(rf);
    }

    @Test
    void rejectsInvalidRequiredAndEnumFormats() {
        assertThrows(
                IllegalArgumentException.class,
                () -> StructuredSchemaRequests.responseFormatFromSchema(
                        "op", "{\"type\":\"object\",\"required\":123,\"properties\":{\"a\":{\"type\":\"string\"}}}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> StructuredSchemaRequests.responseFormatFromSchema(
                        "op", "{\"type\":\"object\",\"required\":[123],\"properties\":{\"a\":{\"type\":\"string\"}}}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> StructuredSchemaRequests.responseFormatFromSchema(
                        "op", "{\"type\":\"object\",\"properties\":{\"a\":{\"enum\":123}}}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> StructuredSchemaRequests.responseFormatFromSchema(
                        "op", "{\"type\":\"object\",\"properties\":{\"a\":{\"enum\":[123]}}}"));
    }

    private static final class ProviderParameters implements ChatRequestParameters {
        private final String vendorValue;
        private final ResponseFormat responseFormat;

        private ProviderParameters(String vendorValue, ResponseFormat responseFormat) {
            this.vendorValue = vendorValue;
            this.responseFormat = responseFormat;
        }

        String vendorValue() {
            return vendorValue;
        }

        @Override
        public String modelName() {
            return null;
        }

        @Override
        public Double temperature() {
            return null;
        }

        @Override
        public Double topP() {
            return null;
        }

        @Override
        public Integer topK() {
            return null;
        }

        @Override
        public Double frequencyPenalty() {
            return null;
        }

        @Override
        public Double presencePenalty() {
            return null;
        }

        @Override
        public Integer maxOutputTokens() {
            return null;
        }

        @Override
        public List<String> stopSequences() {
            return null;
        }

        @Override
        public List<ToolSpecification> toolSpecifications() {
            return null;
        }

        @Override
        public ToolChoice toolChoice() {
            return null;
        }

        @Override
        public ResponseFormat responseFormat() {
            return responseFormat;
        }

        @Override
        public ChatRequestParameters overrideWith(ChatRequestParameters parameters) {
            ResponseFormat nextFormat =
                    parameters.responseFormat() == null ? responseFormat : parameters.responseFormat();
            return new ProviderParameters(vendorValue, nextFormat);
        }

        @Override
        public ChatRequestParameters defaultedBy(ChatRequestParameters parameters) {
            return this;
        }
    }
}
