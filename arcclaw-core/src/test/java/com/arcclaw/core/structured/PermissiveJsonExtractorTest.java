package com.arcclaw.core.structured;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.arcclaw.channel.api.structured.SynthesisResult;

class PermissiveJsonExtractorTest {

    private final PermissiveJsonExtractor extractor = new PermissiveJsonExtractor();

    @Test
    void testPlainJson() {
        String json = "{\"contributions\": [], \"conflictSummary\": [], \"finalResolution\": \"ok\"}";
        SynthesisResult result = extractor.extract(json, SynthesisResult.class);
        assertEquals("ok", result.finalResolution());
        assertTrue(result.contributions().isEmpty());
    }

    @Test
    void testJsonWithMarkdownFences() {
        String json = "```json\n{\"contributions\": [], \"conflictSummary\": [], \"finalResolution\": \"ok\"}\n```";
        SynthesisResult result = extractor.extract(json, SynthesisResult.class);
        assertEquals("ok", result.finalResolution());
    }

    @Test
    void testJsonWithGenericFences() {
        String json = "```\n{\"contributions\": [], \"conflictSummary\": [], \"finalResolution\": \"ok\"}\n```";
        SynthesisResult result = extractor.extract(json, SynthesisResult.class);
        assertEquals("ok", result.finalResolution());
    }

    @Test
    void testTrailingComma() {
        String json = "{\"contributions\": [], \"conflictSummary\": [], \"finalResolution\": \"ok\",}";
        SynthesisResult result = extractor.extract(json, SynthesisResult.class);
        assertEquals("ok", result.finalResolution());
    }

    @Test
    void testSingleQuotes() {
        String json = "{'contributions': [], 'conflictSummary': [], 'finalResolution': 'ok'}";
        SynthesisResult result = extractor.extract(json, SynthesisResult.class);
        assertEquals("ok", result.finalResolution());
    }

    @Test
    void testUnquotedFieldNames() {
        String json = "{contributions: [], conflictSummary: [], finalResolution: \"ok\"}";
        SynthesisResult result = extractor.extract(json, SynthesisResult.class);
        assertEquals("ok", result.finalResolution());
    }

    @Test
    void testMissingNullableField() {
        // additionalInfo is Nullable/not present in JSON
        String json = "{\"contributions\": [], \"conflictSummary\": [], \"finalResolution\": \"ok\"}";
        SynthesisResult result = extractor.extract(json, SynthesisResult.class);
        assertNull(result.additionalInfo());
    }

    @Test
    void testMissingRequiredFieldThrows() {
        // finalResolution is required
        String json = "{\"contributions\": [], \"conflictSummary\": []}";
        assertThrows(JsonExtractionException.class, () -> extractor.extract(json, SynthesisResult.class));
    }

    @Test
    void testInvalidJsonThrows() {
        String json = "this is not json at all";
        assertThrows(JsonExtractionException.class, () -> extractor.extract(json, SynthesisResult.class));
    }

    @Test
    void testComplexNestedStructure() {
        String json = """
            {
              "contributions": [
                { "agentRole": "researcher", "finding": "found X", "metadata": {"confidence": 0.9} }
              ],
              "finalResolution": "X is true",
              "conflictSummary": [
                {
                  "conflictType": "contradiction",
                  "description": "minor diff",
                  "resolutionProposal": "side with researcher"
                }
              ]
            }
            """;
        SynthesisResult result = extractor.extract(json, SynthesisResult.class);
        assertEquals("X is true", result.finalResolution());
        assertEquals(1, result.contributions().size());
        assertEquals(0.9, result.contributions().get(0).metadata().get("confidence"));
        assertNotNull(result.conflictSummary());
        assertFalse(result.conflictSummary().isEmpty());
        assertEquals("minor diff", result.conflictSummary().get(0).description());
    }
}
