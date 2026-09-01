package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amsha.channel.api.AttachmentType;
import com.amsha.llm.api.MultimodalCapability;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CapabilityJsonParser}: validates JSON parsing, heuristic fallback,
 * malformed input handling, and edge cases.
 */
class CapabilityJsonParserTest {

    @Test
    void parsesValidCapabilityJson() {
        String json = """
                {
                  "supportedTypes": ["IMAGE", "AUDIO"],
                  "supportedMimeTypes": ["image/png", "image/jpeg", "audio/wav"],
                  "maxFileSize": 10485760,
                  "maxMediaPerRequest": 5,
                  "supportsInlineBase64": true,
                  "supportsMediaUrls": true
                }
                """;

        MultimodalCapability cap = CapabilityJsonParser.parse(json);

        assertNotNull(cap);
        assertTrue(cap.supportsType(AttachmentType.IMAGE));
        assertTrue(cap.supportsType(AttachmentType.AUDIO));
        assertFalse(cap.supportsType(AttachmentType.DOCUMENT_PDF));
        assertTrue(cap.supportsMimeType("image/png"));
        assertTrue(cap.supportsMimeType("audio/wav"));
        assertEquals(10485760L, cap.maxFileSize());
        assertEquals(5, cap.maxMediaPerRequest());
        assertTrue(cap.supportsInlineBase64());
        assertTrue(cap.supportsMediaUrls());
    }

    @Test
    void parseNullReturnsNull() {
        assertNull(CapabilityJsonParser.parse(null));
    }

    @Test
    void parseBlankReturnsNull() {
        assertNull(CapabilityJsonParser.parse("  "));
    }

    @Test
    void parseMalformedJsonReturnsNull() {
        assertNull(CapabilityJsonParser.parse("not valid json{"));
    }

    @Test
    void resolveUsesJsonWhenPresent() {
        String json = """
                {
                  "supportedTypes": ["DOCUMENT_PDF"],
                  "supportedMimeTypes": ["application/pdf"],
                  "maxFileSize": 25000000,
                  "maxMediaPerRequest": 1,
                  "supportsInlineBase64": false,
                  "supportsMediaUrls": true
                }
                """;

        MultimodalCapability cap = CapabilityJsonParser.resolve(json, "omlx", "some-model");

        assertTrue(cap.supportsType(AttachmentType.DOCUMENT_PDF));
        assertFalse(cap.supportsType(AttachmentType.IMAGE));
    }

    @Test
    void resolveFallsBackToHeuristicWhenJsonNull() {
        // Qwen3.8 is a known open-weight vision model
        MultimodalCapability cap = CapabilityJsonParser.resolve(null, "omlx", "Qwen3.8-27B-4bit");

        assertTrue(cap.supportsType(AttachmentType.IMAGE));
    }

    @Test
    void resolveFallsBackToTextOnlyForUnknownModel() {
        MultimodalCapability cap = CapabilityJsonParser.resolve(null, "omlx", "some-unknown-model");

        assertFalse(cap.isMultimodal());
    }

    @Test
    void parseIgnoresUnknownAttachmentTypes() {
        String json = """
                {
                  "supportedTypes": ["IMAGE", "HOLOGRAM"],
                  "supportedMimeTypes": ["image/png"],
                  "maxFileSize": 0,
                  "maxMediaPerRequest": 0,
                  "supportsInlineBase64": false,
                  "supportsMediaUrls": false
                }
                """;

        MultimodalCapability cap = CapabilityJsonParser.parse(json);

        assertNotNull(cap);
        assertTrue(cap.supportsType(AttachmentType.IMAGE));
        assertEquals(1, cap.supportedTypes().size());
    }

    @Test
    void parseEmptyTypesProducesTextOnly() {
        String json = """
                {
                  "supportedTypes": [],
                  "supportedMimeTypes": [],
                  "maxFileSize": 0,
                  "maxMediaPerRequest": 0,
                  "supportsInlineBase64": false,
                  "supportsMediaUrls": false
                }
                """;

        MultimodalCapability cap = CapabilityJsonParser.parse(json);

        assertNotNull(cap);
        assertFalse(cap.isMultimodal());
    }

    @Test
    void parsesTypeOnlyCapabilityJsonAsMimeWildcard() {
        String json = """
                {
                  "supportedTypes": ["IMAGE"]
                }
                """;

        MultimodalCapability cap = CapabilityJsonParser.parse(json);

        assertNotNull(cap);
        assertTrue(cap.isMultimodal());
        assertTrue(cap.supportsType(AttachmentType.IMAGE));
        assertTrue(cap.supportsMimeType("image/png"));
        assertTrue(cap.supportsMimeType("image/jpeg"));
        assertTrue(cap.supportsInlineBase64());
        assertFalse(cap.supportsType(AttachmentType.AUDIO));
    }
}
