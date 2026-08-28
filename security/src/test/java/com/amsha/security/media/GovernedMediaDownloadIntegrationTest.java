package com.amsha.security.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Map;

import com.amsha.channel.api.AttachmentType;
import com.amsha.channel.api.MediaAttachment;
import com.amsha.core.audit.AuditService;
import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.security.api.OutboundPolicyDecision;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.http.OutboundHttpException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GovernedMediaDownloadIntegrationTest {

    @Mock
    OutboundHttpPolicyService policyService;

    @Mock
    AuditService auditService;

    GovernedMediaDownloadService service;

    @BeforeEach
    void setUp() {
        service = new GovernedMediaDownloadService();
        service.policyService = policyService;
        service.auditService = auditService;
        service.dataDir = "data";
        service.maxImageSizeMb = 10;
        service.maxDocSizeMb = 25;
        service.maxTotalPayloadMb = 50;
        service.connectTimeoutSeconds = 5;
        service.readTimeoutSeconds = 5;
    }

    @Test
    @DisplayName("Initial URI resolving to loopback is blocked by DNS/SSRF validator before connect")
    void download_toLoopback_isBlockedBeforeConnect() {
        URI uri = URI.create("http://127.0.0.1:8080/image.png");
        MediaAttachment attachment =
                MediaAttachment.ofUri("att-lb", AttachmentType.IMAGE, "image/png", "img.png", -1L, uri, Map.of());

        when(policyService.evaluate(
                        eq(uri.toString()), eq(OutboundPurpose.MEDIA_DOWNLOAD), isNull(), eq("user-1"), eq(true)))
                .thenReturn(OutboundPolicyDecision.allow(OutboundPurpose.MEDIA_DOWNLOAD, "user-1"));

        OutboundHttpException ex =
                assertThrows(OutboundHttpException.class, () -> service.download(attachment, "user-1", true));
        assertEquals(OutboundHttpException.Category.DNS_REBINDING, ex.category());
    }

    @Test
    @DisplayName("Initial URI resolving to cloud metadata is blocked by DNS/SSRF validator before connect")
    void download_toMetadata_isBlockedBeforeConnect() {
        URI uri = URI.create("http://169.254.169.254/latest/meta-data/");
        MediaAttachment attachment =
                MediaAttachment.ofUri("att-meta", AttachmentType.IMAGE, "image/png", "meta.png", -1L, uri, Map.of());

        when(policyService.evaluate(
                        eq(uri.toString()), eq(OutboundPurpose.MEDIA_DOWNLOAD), isNull(), eq("user-1"), eq(true)))
                .thenReturn(OutboundPolicyDecision.allow(OutboundPurpose.MEDIA_DOWNLOAD, "user-1"));

        OutboundHttpException ex =
                assertThrows(OutboundHttpException.class, () -> service.download(attachment, "user-1", true));
        assertEquals(OutboundHttpException.Category.DNS_REBINDING, ex.category());
    }
}
