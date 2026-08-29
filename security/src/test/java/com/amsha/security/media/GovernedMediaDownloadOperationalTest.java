package com.amsha.security.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.channel.api.AttachmentType;
import com.amsha.channel.api.MediaAttachment;
import com.amsha.channel.api.MediaTagValues;
import com.amsha.core.audit.AuditService;
import com.amsha.core.scope.ContextAwareExecutorService;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.scope.WorkspaceScopeHolder;
import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.DownloadedMedia;
import com.amsha.security.api.OutboundPolicyDecision;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.http.OutboundHttpException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;

class GovernedMediaDownloadOperationalTest {

    @TempDir
    Path tempDir;

    private GovernedMediaDownloadService downloadService;
    private OutboundHttpPolicyService policyService;
    private AuditService auditService;
    private GovernedTelegramMediaResolver telegramResolver;
    private SimpleMeterRegistry meterRegistry;
    private Vertx vertx;

    private static final byte[] VALID_PNG =
            new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D};

    private static final Set<String> ALLOWED_TAG_KEYS = Set.of("source", "type", "status");
    private static final Set<String> ALLOWED_SOURCE_TAGS =
            Set.of("inline", "uri", "telegram", "unsupported", "unknown");
    private static final Set<String> ALLOWED_TYPE_TAGS = Set.of("image", "audio", "document_pdf", "other");
    private static final Set<String> ALLOWED_STATUS_TAGS = Set.of(
            "success",
            "policy_denied",
            "hitl_required",
            "timeout",
            "redirect_denied",
            "oversized",
            "unsupported_mime",
            "magic_mismatch",
            "security_violation",
            "state_error",
            "error");

    @BeforeEach
    void setUp() {
        policyService = mock(OutboundHttpPolicyService.class);
        auditService = mock(AuditService.class);
        telegramResolver = mock(GovernedTelegramMediaResolver.class);
        meterRegistry = new SimpleMeterRegistry();

        @SuppressWarnings("unchecked")
        Instance<MeterRegistry> meterInstance = mock(Instance.class);
        when(meterInstance.isResolvable()).thenReturn(true);
        when(meterInstance.isUnsatisfied()).thenReturn(false);
        when(meterInstance.get()).thenReturn(meterRegistry);

        downloadService = new GovernedMediaDownloadService();
        downloadService.policyService = policyService;
        downloadService.auditService = auditService;
        downloadService.telegramResolver = telegramResolver;
        downloadService.meterRegistry = meterInstance;
        downloadService.dataDir = tempDir.toString();
        downloadService.maxImageSizeMb = 10;
        downloadService.maxDocSizeMb = 25;
        downloadService.maxTotalPayloadMb = 50;
        downloadService.connectTimeoutSeconds = 5;
        downloadService.readTimeoutSeconds = 10;

        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() {
        if (vertx != null) {
            vertx.close();
        }
        WorkspaceScopeHolder.clear();
    }

    @Test
    void mediaIoRejectsExecutionOnVertxEventLoopThread() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        MediaAttachment attachment =
                MediaAttachment.ofInline("att-1", AttachmentType.IMAGE, "image/png", "sample.png", VALID_PNG, Map.of());

        vertx.runOnContext(_ -> {
            try {
                downloadService.download(attachment, "user-1", false);
            } catch (Throwable t) {
                errorRef.set(t);
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for event loop execution");
        assertNotNull(errorRef.get(), "Expected download to fail on Vert.x event loop");
        assertTrue(errorRef.get() instanceof IllegalStateException);
        assertTrue(errorRef.get().getMessage().contains("event loop"));
    }

    @Test
    void workspaceContextSurvivesContextAwareExecutorServiceWrapping() throws Exception {
        ExecutorService baseExecutor = Executors.newSingleThreadExecutor();
        ContextAwareExecutorService contextExecutor = new ContextAwareExecutorService(baseExecutor);

        AtomicReference<String> capturedSession = new AtomicReference<>();
        AtomicReference<String> capturedUser = new AtomicReference<>();
        AtomicReference<String> capturedWsContextUser = new AtomicReference<>();
        AtomicReference<String> capturedWsContextSession = new AtomicReference<>();
        AtomicReference<DownloadedMedia> downloadedRef = new AtomicReference<>();

        MediaAttachment attachment =
                MediaAttachment.ofInline("att-2", AttachmentType.IMAGE, "image/png", "test.png", VALID_PNG, Map.of());

        WorkspaceContext.run("session-1", "user-1", "chat-1", AutonomyLevel.FULL, "coordinator", () -> {
            try {
                CompletableFuture<Void> future = CompletableFuture.runAsync(
                        WorkspaceScopeHolder.wrap(() -> {
                            capturedSession.set(WorkspaceScopeHolder.getIfActive());
                            capturedUser.set(WorkspaceScopeHolder.getUserIdOrUnknown());
                            capturedWsContextSession.set(
                                    WorkspaceContext.SESSION_ID.isBound() ? WorkspaceContext.SESSION_ID.get() : null);
                            capturedWsContextUser.set(WorkspaceContext.resolveUserId());
                            downloadedRef.set(downloadService.download(attachment, "user-1", false));
                        }),
                        contextExecutor);

                future.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        baseExecutor.shutdown();

        assertEquals("session-1", capturedSession.get(), "Session ID should be propagated across thread boundaries");
        assertEquals("user-1", capturedUser.get(), "User ID should be propagated across thread boundaries");
        assertEquals("session-1", capturedWsContextSession.get(), "WorkspaceContext SESSION_ID should be propagated");
        assertEquals("user-1", capturedWsContextUser.get(), "WorkspaceContext USER_ID should be propagated");
        assertNotNull(downloadedRef.get());
        assertEquals(VALID_PNG.length, downloadedRef.get().sizeBytes());
        downloadedRef.get().close();
    }

    @Test
    void metricTagsContainOnlyBoundedCategoricalValues() {
        MediaAttachment inlineAtt =
                MediaAttachment.ofInline("att-3", AttachmentType.IMAGE, "image/png", "img.png", VALID_PNG, Map.of());

        DownloadedMedia media = downloadService.download(inlineAtt, "user-1", false);
        assertNotNull(media);
        media.close();

        // Oversized error case
        byte[] oversized = new byte[11 * 1024 * 1024];
        MediaAttachment oversizedAtt = MediaAttachment.ofInline(
                "att-oversized", AttachmentType.IMAGE, "image/png", "big.png", oversized, Map.of());
        assertThrows(OutboundHttpException.class, () -> downloadService.download(oversizedAtt, "user-1", false));

        // Policy denied case
        when(policyService.evaluate(anyString(), any(OutboundPurpose.class), any(), anyString(), any(Boolean.class)))
                .thenReturn(OutboundPolicyDecision.deny(
                        "Denied by security policy", OutboundPurpose.MEDIA_DOWNLOAD, "user-1"));
        MediaAttachment remoteAtt = MediaAttachment.ofUri(
                "att-remote",
                AttachmentType.IMAGE,
                "image/png",
                "remote.png",
                0L,
                URI.create("https://example.com/pic.png"),
                Map.of());
        assertThrows(OutboundHttpException.class, () -> downloadService.download(remoteAtt, "user-1", false));

        // HITL required case
        when(policyService.evaluate(anyString(), any(OutboundPurpose.class), any(), anyString(), any(Boolean.class)))
                .thenReturn(OutboundPolicyDecision.requireHitl(
                        "Approval needed", OutboundPurpose.MEDIA_DOWNLOAD, "user-1"));
        MediaAttachment hitlAtt = MediaAttachment.ofUri(
                "att-hitl",
                AttachmentType.IMAGE,
                "image/png",
                "hitl.png",
                0L,
                URI.create("https://example.com/hitl.png"),
                Map.of());
        assertThrows(OutboundHttpException.class, () -> downloadService.download(hitlAtt, "user-1", false));

        // Assert on recorded meters
        List<Meter> meters = meterRegistry.getMeters();
        assertFalse(meters.isEmpty(), "Metrics should be recorded");

        for (Meter meter : meters) {
            String meterName = meter.getId().getName();
            assertTrue(meterName.startsWith("amsha.media."), "Meter name must start with amsha.media: " + meterName);

            for (Tag tag : meter.getId().getTags()) {
                String key = tag.getKey();
                String value = tag.getValue();

                assertTrue(ALLOWED_TAG_KEYS.contains(key), "Tag key must be bounded: " + key);

                switch (key) {
                    case "source" ->
                        assertTrue(ALLOWED_SOURCE_TAGS.contains(value), "Source tag value must be bounded: " + value);
                    case "type" ->
                        assertTrue(ALLOWED_TYPE_TAGS.contains(value), "Type tag value must be bounded: " + value);
                    case "status" ->
                        assertTrue(ALLOWED_STATUS_TAGS.contains(value), "Status tag value must be bounded: " + value);
                }

                // Strictly verify no leakages in tag values
                assertFalse(value.contains("user-1"), "User ID leaked in metric tag: " + value);
                assertFalse(value.contains("example.com"), "URL leaked in metric tag: " + value);
                assertFalse(value.contains("pic.png"), "Filename leaked in metric tag: " + value);
            }
        }

        // Verify specific metric values
        Counter downloadCounter =
                meterRegistry.find("amsha.media.download.total").counter();
        assertNotNull(downloadCounter);
        Timer downloadTimer =
                meterRegistry.find("amsha.media.download.duration").timer();
        assertNotNull(downloadTimer);
    }

    @Test
    void fallbackAndCleanupRemainNonDestructive() throws Exception {
        Path testFile = tempDir.resolve("sample.pdf");
        Files.writeString(testFile, "%PDF-1.4 header content", StandardCharsets.ISO_8859_1);

        DownloadedMedia media = DownloadedMedia.ofTempFile(
                "pdf-1", AttachmentType.DOCUMENT_PDF, "application/pdf", "sample.pdf", Files.size(testFile), testFile);

        assertTrue(Files.exists(testFile));
        media.close();
        assertFalse(Files.exists(testFile), "Temp file should be deterministically cleaned up on close");

        // Close on already closed media should be idempotent and non-destructive
        media.close();
    }

    @Test
    @DisplayName("Malicious inputs and high cardinality strings collapse to unknown tags")
    void maliciousInputsCollapseToUnknownInMetricTags() {
        assertEquals("unknown", MediaTagValues.resolveSource("<script>alert(1)</script>"));
        assertEquals("unknown", MediaTagValues.resolveSource("https://evil.com/token=xyz"));
        assertEquals("unknown", MediaTagValues.resolveStatus("malicious_status_payload"));
        assertEquals("unknown", MediaTagValues.resolveReason("x".repeat(1000)));
    }
}
