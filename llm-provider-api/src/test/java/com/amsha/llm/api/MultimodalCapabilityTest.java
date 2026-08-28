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
                Set.of("image/png", "image/jpeg", "audio/mpeg"),
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
}
