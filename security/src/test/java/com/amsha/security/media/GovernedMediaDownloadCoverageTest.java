package com.amsha.security.media;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.Map;

import com.amsha.channel.api.AttachmentType;
import com.amsha.channel.api.MediaAttachment;
import com.amsha.core.audit.AuditService;
import com.amsha.core.security.OutboundHttpPolicyService;
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
        assertEquals(OutboundHttpException.Category.POLICY_DENIED, ex.category());
    }

    @Test
    void testInlineMediaExceedingLimitThrowsPolicy() {
        byte[] largeData = new byte[2 * 1024 * 1024]; // 2MB > 1MB limit
        MediaAttachment attachment = MediaAttachment.ofInline(
                "att-large", AttachmentType.IMAGE, "image/png", "large.png", largeData, Map.of());

        OutboundHttpException ex =
                assertThrows(OutboundHttpException.class, () -> service.download(attachment, "u1", true));
        assertEquals(OutboundHttpException.Category.POLICY_DENIED, ex.category());
    }
}
