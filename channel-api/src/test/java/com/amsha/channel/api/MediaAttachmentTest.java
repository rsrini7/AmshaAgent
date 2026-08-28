package com.amsha.channel.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MediaAttachmentTest {

    @Test
    @DisplayName("ofUri creates attachment with URI and enforces null inlineData")
    void ofUri_createsValidAttachment() {
        URI uri = URI.create("https://example.com/images/sample.png");
        Map<String, String> meta = Map.of("source", "telegram", "fileId", "12345");

        MediaAttachment attachment =
                MediaAttachment.ofUri("att-1", AttachmentType.IMAGE, "image/png", "sample.png", 1024L, uri, meta);

        assertEquals("att-1", attachment.attachmentId());
        assertEquals(AttachmentType.IMAGE, attachment.type());
        assertEquals("image/png", attachment.mimeType());
        assertEquals("sample.png", attachment.fileName());
        assertEquals(1024L, attachment.sizeBytes());
        assertEquals(uri, attachment.sourceUri());
        assertNull(attachment.inlineData());
        assertEquals(2, attachment.metadata().size());
        assertEquals("telegram", attachment.metadata().get("source"));
    }

    @Test
    @DisplayName("ofInline creates attachment with inline bytes and enforces defensive copying")
    void ofInline_createsValidAttachmentAndDefensivelyCopies() {
        byte[] original = new byte[] {0x1, 0x2, 0x3, 0x4};
        Map<String, String> meta = new HashMap<>();
        meta.put("custom1", "val1");

        MediaAttachment attachment = MediaAttachment.ofInline(
                "att-2", AttachmentType.DOCUMENT_PDF, "application/pdf", "doc.pdf", original, meta);

        // Mutate original array
        original[0] = 0x9;
        // Mutate metadata map
        meta.put("custom2", "val2");

        assertEquals(4L, attachment.sizeBytes());
        assertNull(attachment.sourceUri());
        assertNotNull(attachment.inlineData());
        assertEquals(0x1, attachment.inlineData()[0], "Defensive copy must protect against external array mutation");
        assertEquals(1, attachment.metadata().size(), "Defensive copy must protect against external map mutation");

        // Mutate array returned by getter
        byte[] retrieved = attachment.inlineData();
        retrieved[0] = 0x7;
        assertEquals(0x1, attachment.inlineData()[0], "Getter must return a defensive copy");
    }

    @Test
    @DisplayName("Source exclusivity: rejects both sourceUri and inlineData null")
    void sourceExclusivity_rejectsBothNull() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> new MediaAttachment(
                        "att-3", AttachmentType.IMAGE, "image/png", "sample.png", 0L, null, null, Map.of()));
        assertTrue(ex.getMessage().contains("exactly one source representation"));
    }

    @Test
    @DisplayName("Source exclusivity: rejects both sourceUri and inlineData non-null")
    void sourceExclusivity_rejectsBothNonNull() {
        URI uri = URI.create("https://example.com/test.jpg");
        byte[] bytes = new byte[] {0x1, 0x2};
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> new MediaAttachment(
                        "att-4", AttachmentType.IMAGE, "image/jpeg", "test.jpg", 2L, uri, bytes, Map.of()));
        assertTrue(ex.getMessage().contains("exactly one source representation"));
    }

    @Test
    @DisplayName("Mandatory fields: rejects null attachmentId, type, or mimeType")
    void mandatoryFields_rejectsNulls() {
        URI uri = URI.create("https://example.com/test.jpg");
        assertThrows(
                NullPointerException.class,
                () -> new MediaAttachment(
                        null, AttachmentType.IMAGE, "image/jpeg", "test.jpg", 10L, uri, null, Map.of()));
        assertThrows(
                NullPointerException.class,
                () -> new MediaAttachment("att-5", null, "image/jpeg", "test.jpg", 10L, uri, null, Map.of()));
        assertThrows(
                NullPointerException.class,
                () -> new MediaAttachment("att-6", AttachmentType.IMAGE, null, "test.jpg", 10L, uri, null, Map.of()));
    }

    @Test
    @DisplayName("Advisory size validation: allows -1 and positive sizes, rejects < -1")
    void advisorySize_validation() {
        URI uri = URI.create("https://example.com/test.jpg");
        MediaAttachment unknown =
                MediaAttachment.ofUri("att-u", AttachmentType.IMAGE, "image/jpeg", "test.jpg", -1L, uri, Map.of());
        assertEquals(-1L, unknown.sizeBytes());

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> MediaAttachment.ofUri(
                        "att-neg", AttachmentType.IMAGE, "image/jpeg", "test.jpg", -2L, uri, Map.of()));
        assertTrue(ex.getMessage().contains("sizeBytes must be >= -1"));
    }

    @Test
    @DisplayName("Inline data validation: rejects payloads above the channel contract ceiling")
    void inlineData_rejectsPayloadAboveHardCeiling() {
        byte[] oversized = new byte[(int) MediaAttachment.MAX_INLINE_DATA_BYTES + 1];

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> MediaAttachment.ofInline(
                        "att-large", AttachmentType.IMAGE, "image/png", "large.png", oversized, Map.of()));

        assertTrue(ex.getMessage().contains("channel contract limit"));
    }

    @Test
    @DisplayName("URI scheme validation: allows http, https, telegram, attachment; rejects file and others")
    void uriScheme_validation() {
        URI tgUri = URI.create("telegram://file/AgACAgUAAxkBAAIC1");
        MediaAttachment tg =
                MediaAttachment.ofUri("att-tg", AttachmentType.IMAGE, "image/jpeg", "tg.jpg", 100L, tgUri, Map.of());
        assertEquals(tgUri, tg.sourceUri());

        URI fileScheme = URI.create("file:///etc/passwd");
        assertThrows(
                IllegalArgumentException.class,
                () -> MediaAttachment.ofUri(
                        "att-file", AttachmentType.IMAGE, "image/jpeg", "test.jpg", 10L, fileScheme, Map.of()));

        URI badScheme = URI.create("javascript://alert(1)");
        assertThrows(
                IllegalArgumentException.class,
                () -> MediaAttachment.ofUri(
                        "att-bad", AttachmentType.IMAGE, "image/jpeg", "test.jpg", 10L, badScheme, Map.of()));
    }

    @Test
    @DisplayName("URI credential validation: rejects user-info, sensitive query params, and bot token path")
    void uriCredential_validation() {
        URI userPass = URI.create("https://admin:pass123@example.com/image.png");
        assertThrows(
                IllegalArgumentException.class,
                () -> MediaAttachment.ofUri(
                        "att-user", AttachmentType.IMAGE, "image/png", "img.png", 10L, userPass, Map.of()));

        URI tokenQuery = URI.create("https://example.com/image.png?token=secret-xyz");
        assertThrows(
                IllegalArgumentException.class,
                () -> MediaAttachment.ofUri(
                        "att-token", AttachmentType.IMAGE, "image/png", "img.png", 10L, tokenQuery, Map.of()));

        URI redirectTokenQuery = URI.create("https://example.com/image.png?redirect_token=secret-xyz");
        assertThrows(
                IllegalArgumentException.class,
                () -> MediaAttachment.ofUri(
                        "att-redirect-token",
                        AttachmentType.IMAGE,
                        "image/png",
                        "img.png",
                        10L,
                        redirectTokenQuery,
                        Map.of()));

        URI encodedTokenName = URI.create("https://example.com/image.png?%61ccess_token=secret-xyz");
        assertThrows(
                IllegalArgumentException.class,
                () -> MediaAttachment.ofUri(
                        "att-encoded-token",
                        AttachmentType.IMAGE,
                        "image/png",
                        "img.png",
                        10L,
                        encodedTokenName,
                        Map.of()));

        URI botTokenPath = URI.create("https://api.telegram.org/bot123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11/file.png");
        assertThrows(
                IllegalArgumentException.class,
                () -> MediaAttachment.ofUri(
                        "att-bot", AttachmentType.IMAGE, "image/png", "img.png", 10L, botTokenPath, Map.of()));

        URI tokenPath = URI.create("https://example.com/token/secret/file.png");
        assertThrows(
                IllegalArgumentException.class,
                () -> MediaAttachment.ofUri(
                        "att-tpath", AttachmentType.IMAGE, "image/png", "img.png", 10L, tokenPath, Map.of()));
    }

    @Test
    @DisplayName("URI query validation: matches decoded parameter names without substring false positives")
    void uriQuery_allowsBenignParameterNames() {
        URI benignQuery = URI.create("https://example.com/image.png?monkey=value&tokenized=true");

        MediaAttachment attachment = MediaAttachment.ofUri(
                "att-benign-query", AttachmentType.IMAGE, "image/png", "img.png", 10L, benignQuery, Map.of());

        assertEquals(benignQuery, attachment.sourceUri());
    }

    @Test
    @DisplayName("Metadata credential keyword validation: rejects sensitive keys")
    void metadataCredential_validation() {
        URI uri = URI.create("https://example.com/image.png");
        Map<String, String> badMeta = Map.of("api_key", "secret123");
        assertThrows(
                IllegalArgumentException.class,
                () -> MediaAttachment.ofUri(
                        "att-meta", AttachmentType.IMAGE, "image/png", "img.png", 10L, uri, badMeta));
    }

    @Test
    @DisplayName("toString sanitized: does not expose raw byte contents or secret URLs")
    void toString_isSanitized() {
        byte[] bytes = new byte[] {0x1, 0x2, 0x3};
        MediaAttachment attachment = MediaAttachment.ofInline(
                "att-secret", AttachmentType.AUDIO, "audio/mpeg", "voice.mp3", bytes, Map.of());
        String str = attachment.toString();

        assertTrue(str.contains("att-secret"));
        assertTrue(str.contains("audio/mpeg"));
        assertTrue(str.contains("voice.mp3"));
        assertFalse(str.contains("[1, 2, 3]"), "toString must not dump raw byte contents");
    }
}
