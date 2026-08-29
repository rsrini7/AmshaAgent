package com.amsha.llm.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import com.amsha.channel.api.AttachmentType;

import org.junit.jupiter.api.Test;

class MultimodalCapabilityTest {

    @Test
    void textOnlyConstantHasEmptyCapabilities() {
        MultimodalCapability textOnly = MultimodalCapability.TEXT_ONLY;
        assertFalse(textOnly.isMultimodal());
        assertEquals(0, textOnly.supportedTypes().size());
        assertEquals(0, textOnly.supportedMimeTypes().size());
        assertEquals(0L, textOnly.maxFileSize());
        assertEquals(0, textOnly.maxMediaPerRequest());
        assertFalse(textOnly.supportsInlineBase64());
        assertFalse(textOnly.supportsMediaUrls());
        assertFalse(textOnly.supportsType(AttachmentType.IMAGE));
        assertFalse(textOnly.supportsMimeType("image/png"));
    }

    @Test
    void constructorNormalizesAndCopiesSets() {
        MultimodalCapability cap = new MultimodalCapability(
                Set.of(AttachmentType.IMAGE, AttachmentType.AUDIO),
                Set.of("IMAGE/PNG", "image/jpeg; charset=utf-8", "audio/mpeg"),
                10_000_000L,
                5,
                true,
                false);

        assertTrue(cap.isMultimodal());
        assertTrue(cap.supportsType(AttachmentType.IMAGE));
        assertTrue(cap.supportsType(AttachmentType.AUDIO));
        assertFalse(cap.supportsType(AttachmentType.DOCUMENT_PDF));
        assertFalse(cap.supportsType(AttachmentType.OTHER));

        assertTrue(cap.supportsMimeType("image/png"));
        assertTrue(cap.supportedMimeTypes().contains("image/png"));
        assertTrue(cap.supportedMimeTypes().contains("image/jpeg"));
        assertTrue(cap.supportsMimeType("image/jpeg; charset=utf-8"));
        assertTrue(cap.supportsMimeType("IMAGE/PNG"));
        assertTrue(cap.supportsMimeType("audio/mpeg"));
        assertFalse(cap.supportsMimeType("application/pdf"));
        assertFalse(cap.supportsMimeType(null));
        assertFalse(cap.supportsMimeType(""));

        assertEquals(10_000_000L, cap.maxFileSize());
        assertEquals(5, cap.maxMediaPerRequest());
        assertTrue(cap.supportsInlineBase64());
        assertFalse(cap.supportsMediaUrls());
    }

    @Test
    void constructorClampsNegativeBoundsToZero() {
        MultimodalCapability cap = new MultimodalCapability(null, null, -500L, -10, false, false);

        assertFalse(cap.isMultimodal());
        assertEquals(0L, cap.maxFileSize());
        assertEquals(0, cap.maxMediaPerRequest());
        assertFalse(cap.supportsType(null));
    }

    @Test
    void typeOnlyCapabilityActsAsMimeWildcard() {
        MultimodalCapability typeOnly =
                new MultimodalCapability(Set.of(AttachmentType.IMAGE), Set.of(), 10_000_000L, 5, true, true);

        assertTrue(typeOnly.isMultimodal());
        assertTrue(typeOnly.supportsType(AttachmentType.IMAGE));
        assertFalse(typeOnly.supportsType(AttachmentType.AUDIO));

        // Empty MIME list on a multimodal capability acts as wildcard over its modalities
        assertTrue(typeOnly.supportsMimeType("image/png"));
        assertTrue(typeOnly.supportsMimeType("image/jpeg"));
        assertTrue(typeOnly.supportsMimeType("image/webp"));
        assertFalse(typeOnly.supportsMimeType(null));

        // TEXT_ONLY has empty supportedTypes and still rejects all MIMEs
        assertFalse(MultimodalCapability.TEXT_ONLY.supportsMimeType("image/png"));
    }
}
