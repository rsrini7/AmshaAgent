package com.amsha.security.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import com.amsha.channel.api.AttachmentType;
import com.amsha.channel.api.MediaAttachment;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.security.api.DownloadedMedia;
import com.amsha.security.api.MediaBudgetLimits;
import com.amsha.security.api.OutboundPolicyDecision;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.http.OutboundHttpException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GovernedMediaDownloadServiceTest {

    // Valid 8-byte PNG header followed by some dummy bytes
    private static final byte[] VALID_PNG_BYTES =
            new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D};

    @Mock
    OutboundHttpPolicyService policyService;

    @Mock
    AuditService auditService;

    GovernedMediaDownloadService service;
    HttpServer testServer;

    @BeforeEach
    void setUp() {
        service = new GovernedMediaDownloadService();
        service.policyService = policyService;
        service.auditService = auditService;
        service.telegramResolver = new GovernedTelegramMediaResolver(
                "mock-telegram-token", "https://api.telegram.org", policyService, new ObjectMapper());
        service.dataDir = "data";
        service.maxImageSizeMb = 10;
        service.maxDocSizeMb = 25;
        service.maxTotalPayloadMb = 50;
        service.connectTimeoutSeconds = 5;
        service.readTimeoutSeconds = 10;
    }

    @AfterEach
    void tearDown() {
        if (testServer != null) {
            testServer.stop(0);
        }
    }

    @Test
    @DisplayName("Inline media download succeeds and returns inline DownloadedMedia")
    void download_inlineMedia_success() {
        MediaAttachment attachment = MediaAttachment.ofInline(
                "att-in", AttachmentType.IMAGE, "image/png", "img.png", VALID_PNG_BYTES, Map.of());

        DownloadedMedia media = service.download(attachment, "user-1", true);

        assertEquals("att-in", media.mediaId());
        assertEquals(AttachmentType.IMAGE, media.type());
        assertEquals("image/png", media.mimeType());
        assertEquals(VALID_PNG_BYTES.length, media.sizeBytes());
        assertNotNull(media.inlineData());
        assertEquals((byte) 0x89, media.inlineData()[0]);
        verify(auditService)
                .recordMandatory(
                        "media-download",
                        "user-1",
                        "system",
                        SecurityEventType.MEDIA_DOWNLOADED,
                        "action=media_downloaded type=IMAGE size=" + VALID_PNG_BYTES.length);
    }

    @Test
    @DisplayName("Inline media download supports PDF and Audio types")
    void download_inlineMedia_pdfAndAudio() {
        byte[] pdfBytes = new byte[] {'%', 'P', 'D', 'F', '-', '1', '.', '4', 0x00};
        MediaAttachment pdfAttachment = MediaAttachment.ofInline(
                "att-pdf", AttachmentType.DOCUMENT_PDF, "application/pdf", "doc.pdf", pdfBytes, Map.of());
        DownloadedMedia pdfMedia = service.download(pdfAttachment, "user-1", true);
        assertEquals(AttachmentType.DOCUMENT_PDF, pdfMedia.type());
        assertEquals("application/pdf", pdfMedia.mimeType());

        byte[] oggBytes = new byte[] {'O', 'g', 'g', 'S', 0x00, 0x00};
        MediaAttachment oggAttachment =
                MediaAttachment.ofInline("att-ogg", AttachmentType.AUDIO, "audio/ogg", "audio.ogg", oggBytes, Map.of());
        DownloadedMedia oggMedia = service.download(oggAttachment, "user-1", true);
        assertEquals(AttachmentType.AUDIO, oggMedia.type());
        assertEquals("audio/ogg", oggMedia.mimeType());
    }

    @Test
    @DisplayName("Inline media download rejects deceptive HTML disguised as image")
    void download_inlineMedia_rejectsDeceptiveHtml() {
        byte[] htmlBytes = "<!DOCTYPE html><html><body>malicious</body></html>".getBytes(StandardCharsets.UTF_8);
        MediaAttachment attachment =
                MediaAttachment.ofInline("att-fake", AttachmentType.IMAGE, "image/png", "img.png", htmlBytes, Map.of());

        OutboundHttpException ex =
                assertThrows(OutboundHttpException.class, () -> service.download(attachment, "user-1", true));
        assertEquals(OutboundHttpException.Category.POLICY_DENIED, ex.category());
        assertTrue(ex.getMessage().contains("prohibited script or executable"));
    }

    @Test
    @DisplayName("Inline media download fails if data exceeds limit")
    void download_inlineMedia_exceedsLimit() {
        service.maxImageSizeMb = 1; // 1 MB limit
        byte[] bigData = new byte[2 * 1024 * 1024]; // 2 MB
        System.arraycopy(VALID_PNG_BYTES, 0, bigData, 0, VALID_PNG_BYTES.length);
        MediaAttachment attachment =
                MediaAttachment.ofInline("att-big", AttachmentType.IMAGE, "image/png", "big.png", bigData, Map.of());

        OutboundHttpException ex =
                assertThrows(OutboundHttpException.class, () -> service.download(attachment, "user-1", true));
        assertEquals(OutboundHttpException.Category.OVERSIZED_PAYLOAD, ex.category());
        assertTrue(ex.getMessage().contains("exceeds maximum size limit"));
    }

    @Test
    @DisplayName("downloadAll returns empty list for null or empty input")
    void downloadAll_emptyOrNull() {
        assertTrue(service.downloadAll(null, "user-1", true).isEmpty());
        assertTrue(service.downloadAll(List.of(), "user-1", true).isEmpty());
    }

    @Test
    @DisplayName("downloadAll succeeds for multiple attachments within budget")
    void downloadAll_multipleAttachmentsSuccess() {
        MediaAttachment att1 = MediaAttachment.ofInline(
                "att-1", AttachmentType.IMAGE, "image/png", "1.png", VALID_PNG_BYTES, Map.of());
        MediaAttachment att2 = MediaAttachment.ofInline(
                "att-2", AttachmentType.IMAGE, "image/png", "2.png", VALID_PNG_BYTES, Map.of());

        List<DownloadedMedia> list = service.downloadAll(List.of(att1, att2), "user-1", true);
        assertEquals(2, list.size());
        assertEquals("att-1", list.get(0).mediaId());
        assertEquals("att-2", list.get(1).mediaId());
    }

    @Test
    @DisplayName("downloadAll enforces cumulative turn payload limit across attachments")
    void downloadAll_enforcesCumulativeBudget() {
        service.maxTotalPayloadMb = 1; // 1 MB total turn budget

        byte[] chunk = new byte[600 * 1024]; // 600 KB
        System.arraycopy(VALID_PNG_BYTES, 0, chunk, 0, VALID_PNG_BYTES.length);

        MediaAttachment att1 =
                MediaAttachment.ofInline("att-1", AttachmentType.IMAGE, "image/png", "1.png", chunk, Map.of());
        MediaAttachment att2 =
                MediaAttachment.ofInline("att-2", AttachmentType.IMAGE, "image/png", "2.png", chunk, Map.of());

        // 600KB + 600KB = 1.2MB > 1MB limit
        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class, () -> service.downloadAll(List.of(att1, att2), "user-1", true));
        assertEquals(OutboundHttpException.Category.OVERSIZED_PAYLOAD, ex.category());
        assertTrue(ex.getMessage().contains("maximum size limit"));
    }

    @Test
    @DisplayName("URI media download fails if policy denies access")
    void download_uriMedia_policyDenied() {
        URI uri = URI.create("https://example.com/photo.png");
        MediaAttachment attachment =
                MediaAttachment.ofUri("att-uri", AttachmentType.IMAGE, "image/png", "photo.png", 100L, uri, Map.of());

        when(policyService.evaluate(
                        eq(uri.toString()), eq(OutboundPurpose.MEDIA_DOWNLOAD), isNull(), eq("user-1"), eq(false)))
                .thenReturn(
                        OutboundPolicyDecision.deny("Blacklisted domain", OutboundPurpose.MEDIA_DOWNLOAD, "user-1"));

        OutboundHttpException ex =
                assertThrows(OutboundHttpException.class, () -> service.download(attachment, "user-1", false));
        assertEquals(OutboundHttpException.Category.POLICY_DENIED, ex.category());
        assertTrue(ex.getMessage().contains("Media download denied"));
    }

    @Test
    @DisplayName("URI media download fails with HITL_REQUIRED when not pre-approved")
    void download_uriMedia_hitlRequired() {
        URI uri = URI.create("https://example.com/photo.png");
        MediaAttachment attachment = MediaAttachment.ofUri(
                "att-uri-hitl", AttachmentType.IMAGE, "image/png", "photo.png", 100L, uri, Map.of());

        when(policyService.evaluate(
                        eq(uri.toString()), eq(OutboundPurpose.MEDIA_DOWNLOAD), isNull(), eq("user-1"), eq(false)))
                .thenReturn(OutboundPolicyDecision.requireHitl(
                        "Requires user consent", OutboundPurpose.MEDIA_DOWNLOAD, "user-1"));

        OutboundHttpException ex =
                assertThrows(OutboundHttpException.class, () -> service.download(attachment, "user-1", false));
        assertEquals(OutboundHttpException.Category.HITL_REQUIRED, ex.category());
    }

    @Test
    @DisplayName("Unsupported URI scheme throws POLICY_DENIED")
    void download_unsupportedScheme_throwsPolicyDenied() {
        URI uri = URI.create("attachment://local-ref-123");
        MediaAttachment attachment =
                MediaAttachment.ofUri("att-ref", AttachmentType.IMAGE, "image/png", "photo.png", 100L, uri, Map.of());

        OutboundHttpException ex =
                assertThrows(OutboundHttpException.class, () -> service.download(attachment, "user-1", true));
        assertEquals(OutboundHttpException.Category.POLICY_DENIED, ex.category());
        assertTrue(ex.getMessage().contains("Unsupported media download scheme"));
    }

    @Test
    @DisplayName("Telegram URI scheme fails closed when no valid bot token is configured")
    void download_telegramUri_failsClosedWithoutBotToken() {
        URI tgUri = URI.create("telegram://file/AgACAgUAAxkBAAIC1");
        MediaAttachment attachment =
                MediaAttachment.ofUri("att-tg", AttachmentType.IMAGE, "image/jpeg", "tg.jpg", 100L, tgUri, Map.of());

        OutboundHttpException ex =
                assertThrows(OutboundHttpException.class, () -> service.download(attachment, "user-1", true));
        assertEquals(OutboundHttpException.Category.POLICY_DENIED, ex.category());
        assertTrue(ex.getMessage().contains("no valid bot token configured"));
    }

    @Test
    @DisplayName("Telegram generic binary response uses declared image and audio MIME")
    void resolveResponseMimeType_telegramOctetStreamUsesDeclaredMediaType() {
        MediaAttachment image = MediaAttachment.ofUri(
                "att-tg-image",
                AttachmentType.IMAGE,
                "image/jpeg",
                "photo.jpg",
                -1L,
                URI.create("telegram://file/photo_123"),
                Map.of());
        MediaAttachment mpeg = MediaAttachment.ofUri(
                "att-tg-mpeg",
                AttachmentType.AUDIO,
                "audio/mpeg",
                "audio.mp3",
                -1L,
                URI.create("telegram://file/audio_123"),
                Map.of());
        MediaAttachment ogg = MediaAttachment.ofUri(
                "att-tg-ogg",
                AttachmentType.AUDIO,
                "audio/ogg",
                "voice.ogg",
                -1L,
                URI.create("telegram://file/voice_123"),
                Map.of());

        assertEquals(
                "image/jpeg",
                GovernedMediaDownloadService.resolveResponseMimeType(
                        "application/octet-stream; charset=binary", image));
        assertEquals(
                "audio/mpeg", GovernedMediaDownloadService.resolveResponseMimeType("application/octet-stream", mpeg));
        assertEquals(
                "audio/ogg", GovernedMediaDownloadService.resolveResponseMimeType("application/octet-stream", ogg));
        MediaAttachment wav = MediaAttachment.ofUri(
                "att-tg-wav",
                AttachmentType.AUDIO,
                "audio/x-wav",
                "audio.wav",
                -1L,
                URI.create("telegram://file/audio_wav_123"),
                Map.of());
        assertEquals(
                "audio/wav", GovernedMediaDownloadService.resolveResponseMimeType("application/octet-stream", wav));
    }

    @Test
    @DisplayName("Generic HTTP octet-stream response remains rejected")
    void resolveResponseMimeType_genericHttpOctetStreamRemainsRejected() {
        MediaAttachment external = MediaAttachment.ofUri(
                "att-external",
                AttachmentType.IMAGE,
                "image/jpeg",
                "photo.jpg",
                -1L,
                URI.create("https://example.com/photo.jpg"),
                Map.of());

        assertThrows(
                OutboundHttpException.class,
                () -> GovernedMediaDownloadService.resolveResponseMimeType("application/octet-stream", external));
    }

    @Test
    @DisplayName("Null attachment is rejected")
    void download_nullAttachment_rejected() {
        assertThrows(NullPointerException.class, () -> service.download(null, "user-1", true));
    }

    @Test
    @DisplayName("Streaming media validates a magic header split across short reads")
    void streamResponseBody_acceptsSplitMagicHeader() {
        MediaAttachment attachment = MediaAttachment.ofInline(
                "att-split", AttachmentType.IMAGE, "image/png", "split.png", VALID_PNG_BYTES, Map.of());

        DownloadedMedia media = service.streamResponseBody(
                oneByteAtATime(VALID_PNG_BYTES), attachment, "image/png", VALID_PNG_BYTES.length);

        assertEquals(VALID_PNG_BYTES.length, media.sizeBytes());
        media.close();
    }

    @Test
    @DisplayName("Streaming media creates temp file when exceeding 1MB in-memory threshold")
    void streamResponseBody_createsTempFileForLargePayload() {
        byte[] largeData = new byte[1024 * 1024 + 100]; // ~1MB + 100 bytes
        System.arraycopy(VALID_PNG_BYTES, 0, largeData, 0, VALID_PNG_BYTES.length);

        MediaAttachment attachment = MediaAttachment.ofInline(
                "att-large", AttachmentType.IMAGE, "image/png", "large.png", largeData, Map.of());

        DownloadedMedia media = service.streamResponseBody(
                new ByteArrayInputStream(largeData), attachment, "image/png", largeData.length);

        assertEquals(largeData.length, media.sizeBytes());
        assertNull(media.inlineData());
        assertNotNull(media.tempFilePath());
        assertTrue(Files.exists(media.tempFilePath()));

        media.close();
        assertFalse(Files.exists(media.tempFilePath()), "Temp file must be deleted on close");
    }

    @Test
    @DisplayName("Streaming media times out when a body read stalls")
    void streamResponseBody_timesOutStalledRead() {
        service.readTimeoutSeconds = 1;
        MediaAttachment attachment = MediaAttachment.ofInline(
                "att-stall", AttachmentType.IMAGE, "image/png", "stall.png", VALID_PNG_BYTES, Map.of());

        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class,
                () -> service.streamResponseBody(new StalledInputStream(), attachment, "image/png", 1024));

        assertEquals(OutboundHttpException.Category.TIMEOUT, ex.category());
    }

    @Test
    @DisplayName("Streaming media rejects bytes beyond the remaining turn budget")
    void streamResponseBody_rejectsBytesBeyondLimit() {
        byte[] payload = new byte[] {'O', 'g', 'g', 'S', 0x00};
        MediaAttachment attachment = MediaAttachment.ofInline(
                "att-budget", AttachmentType.AUDIO, "audio/ogg", "budget.ogg", payload, Map.of());

        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class,
                () -> service.streamResponseBody(new ByteArrayInputStream(payload), attachment, "audio/ogg", 4));

        assertEquals(OutboundHttpException.Category.OVERSIZED_PAYLOAD, ex.category());
    }

    @Test
    @DisplayName("Streaming media rejects zero remaining limit bytes")
    void streamResponseBody_rejectsZeroLimit() {
        MediaAttachment attachment = MediaAttachment.ofInline(
                "att-zero", AttachmentType.IMAGE, "image/png", "zero.png", VALID_PNG_BYTES, Map.of());

        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class,
                () -> service.streamResponseBody(
                        new ByteArrayInputStream(VALID_PNG_BYTES), attachment, "image/png", 0));

        assertEquals(OutboundHttpException.Category.OVERSIZED_PAYLOAD, ex.category());
        assertTrue(ex.getMessage().contains("no remaining payload budget"));
    }

    @Test
    @DisplayName("Audit failure closes downloaded media and rethrows")
    void download_auditFailure_closesDownloadedMediaAndThrows() {
        MediaAttachment attachment = MediaAttachment.ofInline(
                "att-audit-fail", AttachmentType.IMAGE, "image/png", "img.png", VALID_PNG_BYTES, Map.of());

        Mockito.doThrow(new RuntimeException("Audit backend unavailable"))
                .when(auditService)
                .recordMandatory(
                        eq("media-download"),
                        eq("user-1"),
                        eq("system"),
                        eq(SecurityEventType.MEDIA_DOWNLOADED),
                        ArgumentMatchers.anyString());

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.download(attachment, "user-1", true));
        assertEquals("Audit backend unavailable", ex.getMessage());
    }

    @Test
    @DisplayName("downloadAll cleans up previously downloaded media if subsequent attachment fails")
    void downloadAll_failureInSubsequentItem_closesAllPreviousMedia() {
        MediaAttachment att1 = MediaAttachment.ofInline(
                "att-1", AttachmentType.IMAGE, "image/png", "1.png", VALID_PNG_BYTES, Map.of());
        byte[] htmlBytes = "<!DOCTYPE html><html>".getBytes(StandardCharsets.UTF_8);
        MediaAttachment att2 =
                MediaAttachment.ofInline("att-2", AttachmentType.IMAGE, "image/png", "2.png", htmlBytes, Map.of());

        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class, () -> service.downloadAll(List.of(att1, att2), "user-1", true));
        assertEquals(OutboundHttpException.Category.POLICY_DENIED, ex.category());
    }

    @Test
    @DisplayName("downloadAll returns empty list for null or empty attachments")
    void downloadAll_nullOrEmpty_returnsEmptyList() {
        assertTrue(service.downloadAll(null, "user-1", true).isEmpty());
        assertTrue(service.downloadAll(List.of(), "user-1", true).isEmpty());
    }

    @Test
    @DisplayName("Effective timeouts fallback to safe defaults when zero or negative")
    void effectiveTimeouts_fallbackToDefaults() {
        service.connectTimeoutSeconds = 0;
        service.readTimeoutSeconds = -5;
        assertEquals(MediaBudgetLimits.DEFAULT_CONNECT_TIMEOUT_SECONDS, service.effectiveConnectTimeoutSeconds());
        assertEquals(MediaBudgetLimits.DEFAULT_READ_TIMEOUT_SECONDS, service.effectiveReadTimeoutSeconds());
    }

    @Test
    @DisplayName("Redirect beyond MAX_REDIRECT_HOPS throws REDIRECT_DENIED")
    void downloadHttpWithRedirects_maxHopsExceeded_throwsRedirectDenied() {
        MediaAttachment attachment = MediaAttachment.ofUri(
                "att-redir",
                AttachmentType.IMAGE,
                "image/png",
                "img.png",
                100L,
                URI.create("https://example.com/img.png"),
                Map.of());

        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class,
                () -> service.downloadHttpWithRedirects(
                        URI.create("https://example.com/img.png"),
                        attachment,
                        "user-1",
                        true,
                        URI.create("https://example.com"),
                        1024 * 1024,
                        MediaBudgetLimits.MAX_REDIRECT_HOPS));
        assertEquals(OutboundHttpException.Category.REDIRECT_DENIED, ex.category());
        assertTrue(ex.getMessage().contains("Too many redirect hops"));
    }

    @Test
    @DisplayName("Telegram resolver null throws IllegalStateException")
    void download_telegramResolverNull_throwsIllegalStateException() {
        service.telegramResolver = null;
        MediaAttachment attachment = MediaAttachment.ofUri(
                "att-tg",
                AttachmentType.IMAGE,
                "image/png",
                "photo.png",
                100L,
                URI.create("telegram://file/123"),
                Map.of());

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> service.download(attachment, "user-1", true));
        assertTrue(ex.getMessage().contains("GovernedTelegramMediaResolver is not available"));
    }

    private static InputStream oneByteAtATime(byte[] bytes) {
        return new InputStream() {
            private int index;

            @Override
            public int read() {
                return index < bytes.length ? bytes[index++] & 0xFF : -1;
            }

            @Override
            public int read(byte[] target, int offset, int length) {
                if (index >= bytes.length) {
                    return -1;
                }
                target[offset] = bytes[index++];
                return 1;
            }
        };
    }

    private static final class StalledInputStream extends InputStream {
        @Override
        public int read() throws IOException {
            return read(new byte[1], 0, 1);
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            try {
                Thread.sleep(2_000L);
                return -1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("test stream interrupted", e);
            }
        }
    }
}
