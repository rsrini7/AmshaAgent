package com.amsha.security.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;

import com.amsha.channel.api.AttachmentType;
import com.amsha.channel.api.MediaAttachment;
import com.amsha.core.audit.AuditService;
import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.security.api.DownloadedMedia;
import com.amsha.security.api.MediaBudgetLimits;
import com.amsha.security.http.OutboundHttpException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GovernedMediaDownloadCoverageTest {

    @Mock
    OutboundHttpPolicyService policyService;

    @Mock
    AuditService auditService;

    private GovernedMediaDownloadService service;

    private static final byte[] VALID_PNG_HEADER = new byte[] {
        (byte) 0x89,
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A,
        0x00,
        0x00,
        0x00,
        0x0D,
        0x49,
        0x48,
        0x44,
        0x52,
        0x00,
        0x00,
        0x00,
        0x01,
        0x00,
        0x00,
        0x00,
        0x01,
        0x08,
        0x06,
        0x00,
        0x00,
        0x00,
        0x1F,
        0x15,
        (byte) 0xC4
    };

    @BeforeEach
    void setUp() {
        service = new GovernedMediaDownloadService();
        service.policyService = policyService;
        service.auditService = auditService;
        service.maxImageSizeMb = 1;
        service.maxDocSizeMb = 1;
        service.maxTotalPayloadMb = 2;
        service.connectTimeoutSeconds = 5;
        service.readTimeoutSeconds = 5;
    }

    @Test
    void testUnsupportedUriSchemeThrowsPolicy() {
        MediaAttachment attachment = MediaAttachment.ofUri(
                "att-1",
                AttachmentType.IMAGE,
                "image/png",
                "img.png",
                100L,
                URI.create("attachment://custom/file.png"),
                Map.of());

        OutboundHttpException ex =
                assertThrows(OutboundHttpException.class, () -> service.download(attachment, "u1", true));
        assertEquals(OutboundHttpException.Category.POLICY_DENIED, ex.category());
        assertTrue(ex.getMessage().contains("Unsupported media download scheme"));
    }

    @Test
    void testStreamResponseBodyZeroBudgetThrowsPolicy() {
        MediaAttachment attachment = MediaAttachment.ofInline(
                "att-1", AttachmentType.IMAGE, "image/png", "img.png", new byte[] {1, 2, 3}, Map.of());

        InputStream in = new ByteArrayInputStream(new byte[] {1, 2, 3});
        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class, () -> service.streamResponseBody(in, attachment, "image/png", 0));
        assertEquals(OutboundHttpException.Category.OVERSIZED_PAYLOAD, ex.category());
    }

    @Test
    void testInlineMediaExceedingLimitThrowsPolicy() {
        byte[] largeData = new byte[2 * 1024 * 1024]; // 2MB > 1MB limit
        MediaAttachment attachment = MediaAttachment.ofInline(
                "att-large", AttachmentType.IMAGE, "image/png", "large.png", largeData, Map.of());

        OutboundHttpException ex =
                assertThrows(OutboundHttpException.class, () -> service.download(attachment, "u1", true));
        assertEquals(OutboundHttpException.Category.OVERSIZED_PAYLOAD, ex.category());
    }

    @Test
    void testDownloadAllNullOrEmptyReturnsEmpty() {
        assertTrue(service.downloadAll(null, "user-1", false).isEmpty());
        assertTrue(service.downloadAll(List.of(), "user-1", false).isEmpty());
    }

    @Test
    void testDownloadAllSuccessInline() {
        MediaAttachment att1 = MediaAttachment.ofInline(
                "att-1", AttachmentType.IMAGE, "image/png", "pic1.png", VALID_PNG_HEADER, Map.of());
        MediaAttachment att2 = MediaAttachment.ofInline(
                "att-2", AttachmentType.IMAGE, "image/png", "pic2.png", VALID_PNG_HEADER, Map.of());

        List<DownloadedMedia> results = service.downloadAll(List.of(att1, att2), "user-1", false);
        assertEquals(2, results.size());
        assertEquals("att-1", results.get(0).mediaId());
        assertEquals("att-2", results.get(1).mediaId());
    }

    @Test
    void testTelegramSchemeWithoutResolverThrows() {
        service.telegramResolver = null;
        MediaAttachment attachment = MediaAttachment.ofUri(
                "att-tg",
                AttachmentType.IMAGE,
                "image/png",
                "tg.png",
                100L,
                URI.create("telegram://file/12345"),
                Map.of());

        assertThrows(IllegalStateException.class, () -> service.download(attachment, "user-1", false));
    }

    @Test
    void testMaxRedirectHopsExceededThrows() {
        MediaAttachment attachment = MediaAttachment.ofUri(
                "att-redir",
                AttachmentType.IMAGE,
                "image/png",
                "redir.png",
                100L,
                URI.create("https://example.com/redir.png"),
                Map.of());

        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class,
                () -> service.downloadHttpWithRedirects(
                        URI.create("https://example.com/redir.png"),
                        attachment,
                        "user-1",
                        false,
                        URI.create("https://example.com/redir.png"),
                        1000L,
                        MediaBudgetLimits.MAX_REDIRECT_HOPS));
        assertEquals(OutboundHttpException.Category.REDIRECT_DENIED, ex.category());
    }

    @Test
    void testTimeoutDefaultsAndSanitizeUrl() {
        service.maxTotalPayloadMb = 0;
        service.connectTimeoutSeconds = 0;
        service.readTimeoutSeconds = 0;

        assertEquals(MediaBudgetLimits.DEFAULT_MAX_TOTAL_PAYLOAD_BYTES, service.maxTotalPayloadBytes());
        assertEquals(MediaBudgetLimits.DEFAULT_CONNECT_TIMEOUT_SECONDS, service.effectiveConnectTimeoutSeconds());
        assertEquals(MediaBudgetLimits.DEFAULT_READ_TIMEOUT_SECONDS, service.effectiveReadTimeoutSeconds());

        assertNull(GovernedMediaDownloadService.sanitizeUrlForPolicy(null));
        assertEquals(
                "https://api.telegram.org/botREDACTED/getFile",
                GovernedMediaDownloadService.sanitizeUrlForPolicy("https://api.telegram.org/bot12345:ABCDEF/getFile"));
    }

    @Test
    void testStreamResponseBodyLargeIntoTempFile() {
        // Generate payload > 1 MB threshold with valid PNG header
        byte[] payload = new byte[1024 * 1024 + 1024]; // 1MB + 1KB
        System.arraycopy(VALID_PNG_HEADER, 0, payload, 0, VALID_PNG_HEADER.length);

        MediaAttachment attachment = MediaAttachment.ofUri(
                "att-large-stream",
                AttachmentType.IMAGE,
                "image/png",
                "large.png",
                (long) payload.length,
                URI.create("https://example.com/large.png"),
                Map.of());

        InputStream in = new ByteArrayInputStream(payload);
        DownloadedMedia result = service.streamResponseBody(in, attachment, "image/png", payload.length + 100);
        assertNotNull(result);
        assertNotNull(result.tempFilePath());
        assertEquals(payload.length, result.sizeBytes());
        result.close();
    }

    @Test
    void testDownloadNullAttachmentThrowsNpe() {
        assertThrows(NullPointerException.class, () -> service.download(null, "u1", false));
    }

    @Test
    void testDownloadAllCumulativeTurnExceededThrows() {
        service.maxTotalPayloadMb = 1; // 1 MB total limit
        byte[] payload1 = new byte[700 * 1024]; // 700 KB
        System.arraycopy(VALID_PNG_HEADER, 0, payload1, 0, VALID_PNG_HEADER.length);
        byte[] payload2 = new byte[700 * 1024]; // 700 KB -> 1.4 MB > 1 MB

        MediaAttachment att1 =
                MediaAttachment.ofInline("att-1", AttachmentType.IMAGE, "image/png", "pic1.png", payload1, Map.of());
        MediaAttachment att2 =
                MediaAttachment.ofInline("att-2", AttachmentType.IMAGE, "image/png", "pic2.png", payload2, Map.of());

        OutboundHttpException ex =
                assertThrows(OutboundHttpException.class, () -> service.downloadAll(List.of(att1, att2), "u1", false));
        assertEquals(OutboundHttpException.Category.OVERSIZED_PAYLOAD, ex.category());
    }
}
