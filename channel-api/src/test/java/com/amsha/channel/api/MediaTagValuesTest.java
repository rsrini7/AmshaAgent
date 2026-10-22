package com.amsha.channel.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.util.Map;

import org.junit.jupiter.api.Test;

class MediaTagValuesTest {

    @Test
    void resolveSourceFromAttachment() {
        assertEquals("unknown", MediaTagValues.resolveSource((MediaAttachment) null));

        MediaAttachment inline = MediaAttachment.ofInline(
                "att-1", AttachmentType.IMAGE, "image/png", "a.png", new byte[] {1, 2, 3}, Map.of());
        assertEquals("inline", MediaTagValues.resolveSource(inline));

        MediaAttachment telegram = MediaAttachment.ofUri(
                "att-2",
                AttachmentType.DOCUMENT_PDF,
                "application/pdf",
                "doc.pdf",
                100L,
                URI.create("telegram://file/abc123"),
                Map.of());
        assertEquals("telegram", MediaTagValues.resolveSource(telegram));

        MediaAttachment http = MediaAttachment.ofUri(
                "att-3",
                AttachmentType.AUDIO,
                "audio/mpeg",
                "audio.mp3",
                200L,
                URI.create("https://example.com/audio.mp3"),
                Map.of());
        assertEquals("uri", MediaTagValues.resolveSource(http));

        MediaAttachment attachmentRef = MediaAttachment.ofUri(
                "att-4",
                AttachmentType.OTHER,
                "application/octet-stream",
                "file.bin",
                300L,
                URI.create("attachment://ref1"),
                Map.of());
        assertEquals("unsupported", MediaTagValues.resolveSource(attachmentRef));
    }

    @Test
    void resolveSourceFromString() {
        assertEquals("unknown", MediaTagValues.resolveSource((String) null));
        assertEquals("unknown", MediaTagValues.resolveSource("  "));
        assertEquals("inline", MediaTagValues.resolveSource("INLINE"));
        assertEquals("telegram", MediaTagValues.resolveSource("Telegram"));
        assertEquals("uri", MediaTagValues.resolveSource("URI"));
        assertEquals("unsupported", MediaTagValues.resolveSource("UNSUPPORTED"));

        // Malicious, high-cardinality, and payload strings must map to "unknown"
        assertEquals("unknown", MediaTagValues.resolveSource("<script>alert(1)</script>"));
        assertEquals("unknown", MediaTagValues.resolveSource("https://evil.com/token=xyz"));
        assertEquals("unknown", MediaTagValues.resolveSource("user_id_12345"));
        assertEquals("unknown", MediaTagValues.resolveSource("a".repeat(1000)));
    }

    @Test
    void resolveType() {
        assertEquals("other", MediaTagValues.resolveType(null));
        assertEquals("image", MediaTagValues.resolveType(AttachmentType.IMAGE));
        assertEquals("audio", MediaTagValues.resolveType(AttachmentType.AUDIO));
        assertEquals("document_pdf", MediaTagValues.resolveType(AttachmentType.DOCUMENT_PDF));
        assertEquals("other", MediaTagValues.resolveType(AttachmentType.OTHER));
    }

    @Test
    void resolveStatusAndReason() {
        assertEquals("unknown", MediaTagValues.resolveStatus(null));
        assertEquals("unknown", MediaTagValues.resolveStatus(" "));
        assertEquals("success", MediaTagValues.resolveStatus("SUCCESS"));
        assertEquals("policy_denied", MediaTagValues.resolveStatus("POLICY_DENIED"));
        assertEquals("hitl_required", MediaTagValues.resolveStatus("HITL_REQUIRED"));
        assertEquals("timeout", MediaTagValues.resolveStatus("timeout"));
        assertEquals("redirect_denied", MediaTagValues.resolveStatus("redirect_denied"));
        assertEquals("security_violation", MediaTagValues.resolveStatus("security_violation"));
        assertEquals("oversized", MediaTagValues.resolveStatus("oversized"));
        assertEquals("unsupported_mime", MediaTagValues.resolveStatus("unsupported_mime"));
        assertEquals("magic_mismatch", MediaTagValues.resolveStatus("magic_mismatch"));
        assertEquals("state_error", MediaTagValues.resolveStatus("state_error"));

        // Arbitrary status must map to "unknown"
        assertEquals("unknown", MediaTagValues.resolveStatus("arbitrary_status"));
        assertEquals("unknown", MediaTagValues.resolveStatus("SELECT * FROM users"));
        assertEquals("unknown", MediaTagValues.resolveStatus("secret_token_123"));

        assertEquals("unknown", MediaTagValues.resolveReason(null));
        assertEquals("unknown", MediaTagValues.resolveReason(" "));
        assertEquals("unsupported_modality", MediaTagValues.resolveReason("UNSUPPORTED_MODALITY"));
        assertEquals("audio_too_large", MediaTagValues.resolveReason("audio_too_large"));
        assertEquals("transcription_failed", MediaTagValues.resolveReason("transcription_failed"));
        assertEquals("oversized", MediaTagValues.resolveReason("Oversized"));
        assertEquals("cumulative_exceeded", MediaTagValues.resolveReason("cumulative_exceeded"));
        assertEquals("count_exceeded", MediaTagValues.resolveReason("count_exceeded"));
        assertEquals("unavailable", MediaTagValues.resolveReason("unavailable"));
        assertEquals("extraction_error", MediaTagValues.resolveReason("extraction_error"));
        assertEquals("transport_unsupported", MediaTagValues.resolveReason("transport_unsupported"));

        // Arbitrary reason must map to "unknown"
        assertEquals("unknown", MediaTagValues.resolveReason("arbitrary_reason_payload"));
        assertEquals("unknown", MediaTagValues.resolveReason("file:///etc/passwd"));
        assertEquals("unknown", MediaTagValues.resolveReason("x".repeat(500)));
    }
}
