package com.amsha.security.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.amsha.channel.api.AttachmentType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DownloadedMediaTest {

    @Test
    @DisplayName("ofInline creates valid container and protects inline bytes with defensive copy")
    void ofInline_createsValidContainer() {
        byte[] bytes = new byte[] {0x1, 0x2, 0x3};
        DownloadedMedia media = DownloadedMedia.ofInline("m-1", AttachmentType.IMAGE, "image/png", "test.png", bytes);

        // Mutate original
        bytes[0] = 0x9;

        assertEquals("m-1", media.mediaId());
        assertEquals(AttachmentType.IMAGE, media.type());
        assertEquals("image/png", media.mimeType());
        assertEquals("test.png", media.fileName());
        assertEquals(3L, media.sizeBytes());
        assertNull(media.tempFilePath());
        assertNotNull(media.inlineData());
        assertEquals(0x1, media.inlineData()[0], "Defensive copy must protect internal bytes");

        // Mutate getter copy
        byte[] retrieved = media.inlineData();
        retrieved[0] = 0x5;
        assertEquals(0x1, media.inlineData()[0], "Getter must return a defensive copy");

        // Close on inline media is safe no-op
        media.close();
    }

    @Test
    @DisplayName("ofTempFile creates valid container and deterministically deletes file on close")
    void ofTempFile_deletesFileOnClose() throws IOException {
        Path tempFile = Files.createTempFile("test_media_", ".tmp");
        Files.writeString(tempFile, "hello media content");
        assertTrue(Files.exists(tempFile));

        DownloadedMedia media = DownloadedMedia.ofTempFile(
                "m-2", AttachmentType.DOCUMENT_PDF, "application/pdf", "doc.pdf", 19L, tempFile);

        assertEquals("m-2", media.mediaId());
        assertEquals(AttachmentType.DOCUMENT_PDF, media.type());
        assertNull(media.inlineData());
        assertEquals(tempFile, media.tempFilePath());

        // Close deletes temp file
        media.close();
        assertFalse(Files.exists(tempFile), "AutoCloseable close() must deterministically delete temp file");
    }

    @Test
    @DisplayName("Invariants: enforces source XOR (either inline or tempFile, not both or neither)")
    void invariants_sourceXor() throws IOException {
        Path tempFile = Files.createTempFile("test_media_xor_", ".tmp");
        try {
            // Both null
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new DownloadedMedia("m-xor1", AttachmentType.IMAGE, "image/png", "img.png", 0L, null, null));

            // Both non-null
            byte[] data = new byte[] {1, 2};
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new DownloadedMedia(
                            "m-xor2", AttachmentType.IMAGE, "image/png", "img.png", 2L, data, tempFile));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    @DisplayName("Invariants: rejects negative sizeBytes or mismatched inline byte length")
    void invariants_sizeValidation() {
        byte[] data = new byte[] {1, 2, 3};

        // Negative size
        assertThrows(
                IllegalArgumentException.class,
                () -> new DownloadedMedia("m-neg", AttachmentType.IMAGE, "image/png", "img.png", -1L, data, null));

        // Mismatched size
        assertThrows(
                IllegalArgumentException.class,
                () -> new DownloadedMedia(
                        "m-mismatch", AttachmentType.IMAGE, "image/png", "img.png", 100L, data, null));
    }

    @Test
    @DisplayName("toString does not leak raw payload bytes or sensitive data")
    void toString_sanitized() {
        DownloadedMedia media =
                DownloadedMedia.ofInline("m-3", AttachmentType.AUDIO, "audio/mpeg", "voice.mp3", new byte[] {1, 2, 3});
        String str = media.toString();
        assertTrue(str.contains("m-3"));
        assertTrue(str.contains("audio/mpeg"));
        assertFalse(str.contains("[1, 2, 3]"));
    }
}
