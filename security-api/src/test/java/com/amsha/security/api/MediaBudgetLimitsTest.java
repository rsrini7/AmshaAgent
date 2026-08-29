package com.amsha.security.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.amsha.channel.api.AttachmentType;
import com.amsha.channel.api.MediaAttachment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MediaBudgetLimitsTest {

    @Test
    @DisplayName("Constants and MIME allowlist match architectural requirements")
    void constants_andAllowlist() {
        assertEquals(10L * 1024L * 1024L, MediaBudgetLimits.DEFAULT_MAX_IMAGE_SIZE_BYTES);
        assertEquals(25L * 1024L * 1024L, MediaBudgetLimits.DEFAULT_MAX_DOCUMENT_SIZE_BYTES);
        assertEquals(50L * 1024L * 1024L, MediaBudgetLimits.DEFAULT_MAX_TOTAL_PAYLOAD_BYTES);
        assertEquals(10, MediaBudgetLimits.DEFAULT_CONNECT_TIMEOUT_SECONDS);
        assertEquals(30, MediaBudgetLimits.DEFAULT_READ_TIMEOUT_SECONDS);
        assertEquals(5, MediaBudgetLimits.MAX_REDIRECT_HOPS);

        assertTrue(MediaBudgetLimits.ALLOWED_MIME_TYPES.contains("image/png"));
        assertTrue(MediaBudgetLimits.ALLOWED_MIME_TYPES.contains("image/jpeg"));
        assertTrue(MediaBudgetLimits.ALLOWED_MIME_TYPES.contains("image/webp"));
        assertTrue(MediaBudgetLimits.ALLOWED_MIME_TYPES.contains("image/gif"));
        assertTrue(MediaBudgetLimits.ALLOWED_MIME_TYPES.contains("application/pdf"));
        assertTrue(MediaBudgetLimits.ALLOWED_MIME_TYPES.contains("audio/mpeg"));
        assertTrue(MediaBudgetLimits.ALLOWED_MIME_TYPES.contains("audio/wav"));
        assertTrue(MediaBudgetLimits.ALLOWED_MIME_TYPES.contains("audio/ogg"));
    }

    @Test
    @DisplayName("resolveLimitForType resolves correct limits for IMAGE vs DOCUMENT/AUDIO/OTHER")
    void resolveLimitForType_defaultsAndCustom() {
        assertEquals(
                MediaBudgetLimits.DEFAULT_MAX_IMAGE_SIZE_BYTES,
                MediaBudgetLimits.resolveLimitForType(AttachmentType.IMAGE, 0, 0));
        assertEquals(
                MediaBudgetLimits.DEFAULT_MAX_DOCUMENT_SIZE_BYTES,
                MediaBudgetLimits.resolveLimitForType(AttachmentType.DOCUMENT_PDF, 0, 0));
        assertEquals(
                MediaBudgetLimits.DEFAULT_MAX_DOCUMENT_SIZE_BYTES,
                MediaBudgetLimits.resolveLimitForType(AttachmentType.AUDIO, 0, 0));
        assertEquals(
                MediaBudgetLimits.DEFAULT_MAX_DOCUMENT_SIZE_BYTES,
                MediaBudgetLimits.resolveLimitForType(AttachmentType.OTHER, 0, 0));

        assertEquals(
                20L * 1024L * 1024L,
                MediaBudgetLimits.resolveLimitForType(AttachmentType.IMAGE, 20L * 1024L * 1024L, 50L * 1024L * 1024L));
        assertEquals(
                50L * 1024L * 1024L,
                MediaBudgetLimits.resolveLimitForType(
                        AttachmentType.DOCUMENT_PDF, 20L * 1024L * 1024L, 50L * 1024L * 1024L));
    }

    @Test
    @DisplayName("MediaDownloadPort default downloadAll enforces cumulative limits")
    void mediaDownloadPort_defaultDownloadAll() {
        MediaDownloadPort port = new MediaDownloadPort() {
            @Override
            public DownloadedMedia download(MediaAttachment attachment, String actorUserId, boolean preApproved) {
                return DownloadedMedia.ofInline(
                        attachment.attachmentId(),
                        attachment.type(),
                        attachment.mimeType(),
                        attachment.fileName(),
                        attachment.inlineData());
            }
        };

        assertTrue(port.downloadAll(null, "u1", true).isEmpty());
        assertTrue(port.downloadAll(List.of(), "u1", true).isEmpty());

        byte[] small = new byte[] {1, 2, 3};
        MediaAttachment att =
                MediaAttachment.ofInline("a1", AttachmentType.IMAGE, "image/png", "1.png", small, Map.of());
        List<DownloadedMedia> res = port.downloadAll(List.of(att), "u1", true);
        assertEquals(1, res.size());
        assertEquals("a1", res.get(0).mediaId());

        MediaDownloadPort nullResultPort = (attachment, actorUserId, preApproved) -> null;
        assertThrows(IllegalStateException.class, () -> nullResultPort.downloadAll(List.of(att), "u1", true));

        MediaDownloadPort configuredLimitPort = new MediaDownloadPort() {
            @Override
            public DownloadedMedia download(MediaAttachment attachment, String actorUserId, boolean preApproved) {
                return DownloadedMedia.ofInline(
                        attachment.attachmentId(),
                        attachment.type(),
                        attachment.mimeType(),
                        attachment.fileName(),
                        attachment.inlineData());
            }

            @Override
            public long maxTotalPayloadBytes() {
                return 4L;
            }
        };
        assertThrows(IllegalStateException.class, () -> configuredLimitPort.downloadAll(List.of(att, att), "u1", true));

        // Test cumulative limit breach in default port
        byte[] big = new byte[30 * 1024 * 1024]; // 30MB
        MediaAttachment bigAtt1 =
                MediaAttachment.ofInline("b1", AttachmentType.IMAGE, "image/png", "1.png", big, Map.of());
        MediaAttachment bigAtt2 =
                MediaAttachment.ofInline("b2", AttachmentType.IMAGE, "image/png", "2.png", big, Map.of());

        // 30MB + 30MB = 60MB > 50MB default limit
        assertThrows(IllegalStateException.class, () -> port.downloadAll(List.of(bigAtt1, bigAtt2), "u1", true));
    }
}
