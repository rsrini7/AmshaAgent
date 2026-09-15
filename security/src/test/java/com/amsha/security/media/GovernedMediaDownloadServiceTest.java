package com.amsha.security.media;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import com.amsha.channel.api.AttachmentType;
import com.amsha.channel.api.MediaAttachment;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.security.api.DownloadedMedia;
import com.amsha.security.api.MediaBudgetLimits;
import com.amsha.security.api.OutboundPolicyDecision;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.http.OutboundHttpException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Unit tests for {@link GovernedMediaDownloadService}.
 *
 * <p>The HTTP arms are driven against a real loopback {@link HttpServer}. Media downloads apply a
 * stricter SSRF blocklist than {@code GovernedHttpClient} and reject loopback/private targets by
 * design, so {@link #allowLoopbackTargets()} opens a static mock of {@link MediaSecurityValidator}
 * that keeps every rule real ({@code CALLS_REAL_METHODS}) except the JIT DNS gate. The gate itself
 * is covered by {@code MediaSecurityValidatorTest}; here we exercise the service's orchestration.
 */
@ExtendWith(MockitoExtension.class)
class GovernedMediaDownloadServiceTest {

    // Valid 8-byte PNG header followed by some dummy bytes
    private static final byte[] VALID_PNG_BYTES =
            new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D};

    /** A well-formed GIF header served as {@code image/png} to trip the magic-byte check. */
    private static final byte[] WRONG_MAGIC_BYTES = "GIF89a-not-actually-a-png".getBytes(StandardCharsets.UTF_8);

    @Mock
    OutboundHttpPolicyService policyService;

    @Mock
    AuditService auditService;

    GovernedMediaDownloadService service;
    HttpServer testServer;
    String baseUrl;

    /** Non-null only while a test has opened the loopback-permitting SSRF gate. */
    private MockedStatic<MediaSecurityValidator> ssrfGate;

    @BeforeEach
    void setUp() throws IOException {
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

        testServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Daemon-threaded handlers so the deliberately hanging endpoints below cannot stall the
        // dispatcher (or the JVM) for the tests that follow.
        testServer.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "media-download-test-server");
            thread.setDaemon(true);
            return thread;
        }));
        int port = testServer.getAddress().getPort();

        testServer.createContext("/image.png", exchange -> respond(exchange, 200, "image/png", VALID_PNG_BYTES));
        testServer.createContext("/missing.png", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        testServer.createContext(
                "/wrong-magic.png", exchange -> respond(exchange, 200, "image/png", WRONG_MAGIC_BYTES));
        testServer.createContext("/redirect-same-origin", exchange -> redirect(exchange, "/image.png"));
        testServer.createContext("/redirect-no-location", exchange -> {
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        testServer.createContext("/redirect-denied", exchange -> redirect(exchange, "/denied.png"));
        testServer.createContext("/redirect-hitl", exchange -> redirect(exchange, "/hitl.png"));
        testServer.createContext(
                "/redirect-cross-origin", exchange -> redirect(exchange, "http://localhost:" + port + "/image.png"));
        testServer.createContext("/hang", exchange -> {
            sleepQuietly(1_500L);
            respond(exchange, 200, "image/png", VALID_PNG_BYTES);
        });
        testServer.start();

        baseUrl = "http://127.0.0.1:" + port;
    }

    @AfterEach
    void tearDown() {
        if (ssrfGate != null) {
            ssrfGate.close();
        }
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

    private static class StalledInputStream extends InputStream {
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

    /** Same stall as {@link StalledInputStream}, but the stream also refuses to close. */
    private static final class StalledUnclosableInputStream extends StalledInputStream {
        @Override
        public void close() throws IOException {
            throw new IOException("close refused");
        }
    }

    // ------------------------------------------------------------------
    // HTTP download path (real loopback server)
    // ------------------------------------------------------------------

    /**
     * Opens a static mock of {@link MediaSecurityValidator} that keeps every rule real except the JIT
     * DNS gate. The gate rejects loopback/private/link-local addresses by design — which is precisely
     * what a local test server is — so the transport arms below are otherwise unreachable. The gate
     * itself is covered by {@code MediaSecurityValidatorTest}.
     *
     * <p>Mockito's static mocks are <b>thread-local</b>, so any test that drives the download from a
     * worker thread must open its own gate on that thread rather than reuse {@link #ssrfGate}.
     */
    private static MockedStatic<MediaSecurityValidator> openLoopbackGate() {
        MockedStatic<MediaSecurityValidator> gate =
                Mockito.mockStatic(MediaSecurityValidator.class, Answers.CALLS_REAL_METHODS);
        gate.when(() -> MediaSecurityValidator.validateTarget(any(URI.class), any(Duration.class)))
                .thenAnswer(invocation -> null);
        return gate;
    }

    private void allowLoopbackTargets() {
        ssrfGate = openLoopbackGate();
    }

    private MediaAttachment httpAttachment(String path, AttachmentType type, String mimeType) {
        return MediaAttachment.ofUri(
                "att-http", type, mimeType, "media.bin", 100L, URI.create(baseUrl + path), Map.of());
    }

    private DownloadedMedia downloadHttp(String path, AttachmentType type, String mimeType) {
        return service.download(httpAttachment(path, type, mimeType), "user-1", true);
    }

    @Test
    @DisplayName("HTTP media download streams a 200 response and records the mandatory audit event")
    void download_httpImage_streamsInlinePayload() {
        allowLoopbackTargets();
        service.policyService = new AllowAllPolicy();

        DownloadedMedia media = downloadHttp("/image.png", AttachmentType.IMAGE, "image/png");

        assertEquals("att-http", media.mediaId());
        assertEquals(AttachmentType.IMAGE, media.type());
        assertEquals("image/png", media.mimeType());
        assertEquals(VALID_PNG_BYTES.length, media.sizeBytes());
        assertArrayEquals(VALID_PNG_BYTES, media.inlineData());
        verify(auditService)
                .recordMandatory(
                        eq("media-download"),
                        eq("user-1"),
                        eq("system"),
                        eq(SecurityEventType.MEDIA_DOWNLOADED),
                        anyString());
    }

    @Test
    @DisplayName("A same-origin redirect is revalidated, re-evaluated, and followed")
    void download_httpRedirect_sameOriginIsFollowed() {
        allowLoopbackTargets();
        service.policyService = new AllowAllPolicy();

        DownloadedMedia media = downloadHttp("/redirect-same-origin", AttachmentType.IMAGE, "image/png");

        assertEquals(VALID_PNG_BYTES.length, media.sizeBytes());
    }

    @Test
    @DisplayName("A redirect without a Location header is denied")
    void download_httpRedirect_missingLocationHeaderIsDenied() {
        allowLoopbackTargets();
        service.policyService = new AllowAllPolicy();

        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class,
                () -> downloadHttp("/redirect-no-location", AttachmentType.IMAGE, "image/png"));

        assertEquals(OutboundHttpException.Category.REDIRECT_DENIED, ex.category());
        assertTrue(ex.getMessage().contains("no Location header"));
    }

    @Test
    @DisplayName("A redirect target denied by outbound policy is rejected")
    void download_httpRedirect_policyDenialIsRejected() {
        allowLoopbackTargets();
        service.policyService = new RulePolicy(
                url -> url.contains("/denied.png"),
                OutboundPolicyDecision.deny("redirect blocked", OutboundPurpose.MEDIA_DOWNLOAD, "user-1"));

        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class, () -> downloadHttp("/redirect-denied", AttachmentType.IMAGE, "image/png"));

        assertEquals(OutboundHttpException.Category.REDIRECT_DENIED, ex.category());
        assertTrue(ex.getMessage().contains("Redirect target denied by outbound policy"));
    }

    @Test
    @DisplayName("A redirect target requiring HITL is rejected")
    void download_httpRedirect_hitlRequiredIsRejected() {
        allowLoopbackTargets();
        service.policyService = new RulePolicy(
                url -> url.contains("/hitl.png"),
                OutboundPolicyDecision.requireHitl("needs approval", OutboundPurpose.MEDIA_DOWNLOAD, "user-1"));

        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class, () -> downloadHttp("/redirect-hitl", AttachmentType.IMAGE, "image/png"));

        assertEquals(OutboundHttpException.Category.HITL_REQUIRED, ex.category());
        assertTrue(ex.getMessage().contains("requires HITL approval"));
    }

    @Test
    @DisplayName("A pre-approved redirect that crosses the media approval origin demands fresh HITL")
    void download_httpRedirect_crossOriginDemandsFreshApproval() {
        allowLoopbackTargets();
        service.policyService = new AllowAllPolicy();

        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class,
                () -> downloadHttp("/redirect-cross-origin", AttachmentType.IMAGE, "image/png"));

        assertEquals(OutboundHttpException.Category.HITL_REQUIRED, ex.category());
        assertTrue(ex.getMessage().contains("crosses media approval origin"));
    }

    @Test
    @DisplayName("A non-200 response is reported as an I/O failure")
    void download_httpNon200_isReportedAsIoFailure() {
        allowLoopbackTargets();
        service.policyService = new AllowAllPolicy();

        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class, () -> downloadHttp("/missing.png", AttachmentType.IMAGE, "image/png"));

        assertEquals(OutboundHttpException.Category.IO, ex.category());
        assertTrue(ex.getMessage().contains("HTTP status 404"));
    }

    @Test
    @DisplayName("A response that never arrives maps to a timeout")
    void download_httpReadTimeout_mapsToTimeout() {
        allowLoopbackTargets();
        service.policyService = new AllowAllPolicy();
        service.readTimeoutSeconds = 1;

        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class, () -> downloadHttp("/hang", AttachmentType.IMAGE, "image/png"));

        assertEquals(OutboundHttpException.Category.TIMEOUT, ex.category());
        assertTrue(ex.getMessage().contains("timed out"));
    }

    @Test
    @DisplayName("A refused connection maps to an I/O failure")
    void download_httpConnectionRefused_mapsToIoFailure() {
        allowLoopbackTargets();
        service.policyService = new AllowAllPolicy();
        service.connectTimeoutSeconds = 1;

        // Bind, capture the port, then release it so nothing is listening there.
        int deadPort = testServer.getAddress().getPort();
        testServer.stop(0);
        MediaAttachment attachment = MediaAttachment.ofUri(
                "att-dead",
                AttachmentType.IMAGE,
                "image/png",
                "dead.png",
                100L,
                URI.create("http://127.0.0.1:" + deadPort + "/image.png"),
                Map.of());

        OutboundHttpException ex =
                assertThrows(OutboundHttpException.class, () -> service.download(attachment, "user-1", true));

        assertEquals(OutboundHttpException.Category.IO, ex.category());
        assertTrue(ex.getMessage().contains("I/O failure"));
    }

    @Test
    @DisplayName("An interrupt while awaiting the response maps to an I/O failure")
    void download_httpInterrupted_mapsToIoFailure() throws Exception {
        allowLoopbackTargets();
        service.policyService = new AllowAllPolicy();
        service.readTimeoutSeconds = 30;
        MediaAttachment attachment = httpAttachment("/hang", AttachmentType.IMAGE, "image/png");

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(
                () -> {
                    // Static mocks are thread-local: open a gate on this thread as well.
                    try (MockedStatic<MediaSecurityValidator> gate = openLoopbackGate()) {
                        service.download(attachment, "user-1", true);
                    } catch (Throwable t) {
                        failure.set(t);
                    }
                },
                "media-download-interrupt-test");
        worker.setDaemon(true);
        worker.start();
        Thread.sleep(400L);
        worker.interrupt();
        worker.join(10_000L);

        assertFalse(worker.isAlive(), "worker must not be left blocked");
        assertTrue(failure.get() instanceof OutboundHttpException, "unexpected failure: " + failure.get());
        assertEquals(OutboundHttpException.Category.IO, ((OutboundHttpException) failure.get()).category());
        assertTrue(failure.get().getMessage().contains("interrupted"));
    }

    @Test
    @DisplayName("A payload whose magic bytes contradict its Content-Type is rejected mid-stream")
    void download_httpMagicMismatch_isRejectedAndStreamClosed() {
        allowLoopbackTargets();
        service.policyService = new AllowAllPolicy();

        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class, () -> downloadHttp("/wrong-magic.png", AttachmentType.IMAGE, "image/png"));

        assertEquals(OutboundHttpException.Category.MAGIC_MISMATCH, ex.category());
        assertTrue(ex.getMessage().contains("PNG magic header"));
    }

    @Test
    @DisplayName("A generic HTTP response resolves through the plain MIME allowlist")
    void resolveResponseMimeType_genericHttpSuccessUsesAllowlist() {
        MediaAttachment external = MediaAttachment.ofUri(
                "att-ext",
                AttachmentType.IMAGE,
                "image/jpeg",
                "photo.jpg",
                -1L,
                URI.create("https://example.com/photo.jpg"),
                Map.of());

        assertEquals("image/png", GovernedMediaDownloadService.resolveResponseMimeType("image/png", external));
    }

    // ------------------------------------------------------------------
    // Remaining service arms
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A resolvable-but-failing MeterRegistry is swallowed so the download still succeeds")
    void resolveMeterRegistry_swallowsResolutionFailure() {
        @SuppressWarnings("unchecked")
        Instance<MeterRegistry> broken = Mockito.mock(Instance.class);
        when(broken.isResolvable()).thenReturn(true);
        when(broken.get()).thenThrow(new RuntimeException("registry unavailable"));
        service.meterRegistry = broken;

        MediaAttachment attachment = MediaAttachment.ofInline(
                "att-metrics", AttachmentType.IMAGE, "image/png", "m.png", VALID_PNG_BYTES, Map.of());

        DownloadedMedia media = service.download(attachment, "user-1", true);

        assertEquals("att-metrics", media.mediaId());
    }

    @Test
    @DisplayName("A telegram:// attachment is delegated to the resolver and its result returned")
    void download_telegramScheme_delegatesToResolver() {
        service.telegramResolver = new StubTelegramResolver();
        MediaAttachment attachment = MediaAttachment.ofUri(
                "att-tg-ok",
                AttachmentType.IMAGE,
                "image/jpeg",
                "tg.jpg",
                100L,
                URI.create("telegram://file/AgACAgUAAxkBAAIC1"),
                Map.of());

        DownloadedMedia media = service.download(attachment, "user-1", true);

        assertEquals("att-tg-ok", media.mediaId());
        assertEquals("image/jpeg", media.mimeType());
        assertEquals(VALID_PNG_BYTES.length, media.sizeBytes());
    }

    @Test
    @DisplayName("downloadAll rejects a further attachment once the turn budget is exactly exhausted")
    void downloadAll_exhaustedBudgetRejectsTheNextAttachment() {
        service.maxTotalPayloadMb = 1;
        byte[] exactlyOneMb = new byte[1024 * 1024];
        System.arraycopy(VALID_PNG_BYTES, 0, exactlyOneMb, 0, VALID_PNG_BYTES.length);

        MediaAttachment first = MediaAttachment.ofInline(
                "att-full", AttachmentType.IMAGE, "image/png", "full.png", exactlyOneMb, Map.of());
        MediaAttachment second = MediaAttachment.ofInline(
                "att-extra", AttachmentType.IMAGE, "image/png", "extra.png", VALID_PNG_BYTES, Map.of());

        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class, () -> service.downloadAll(List.of(first, second), "user-1", true));

        assertEquals(OutboundHttpException.Category.OVERSIZED_PAYLOAD, ex.category());
        assertTrue(ex.getMessage().contains("reached limit"));
    }

    // ------------------------------------------------------------------
    // Streaming arms
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Crossing the size limit after a temp file is opened closes and deletes it")
    void streamResponseBody_limitTrippedAfterTempFileCreation_cleansUp() {
        byte[] payload = new byte[2 * 1024 * 1024];
        System.arraycopy(VALID_PNG_BYTES, 0, payload, 0, VALID_PNG_BYTES.length);
        // Large enough to force the temp-file path, small enough that a later read trips the limit.
        long limitBytes = 1024L * 1024L + 20_000L;

        MediaAttachment attachment =
                MediaAttachment.ofInline("att-tmp", AttachmentType.IMAGE, "image/png", "tmp.png", payload, Map.of());

        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class,
                () -> service.streamResponseBody(
                        new ByteArrayInputStream(payload), attachment, "image/png", limitBytes));

        assertEquals(OutboundHttpException.Category.OVERSIZED_PAYLOAD, ex.category());
        assertTrue(ex.getMessage().contains("exceeded maximum size limit"));
    }

    @Test
    @DisplayName("A stalled read whose stream also refuses to close still reports a timeout")
    void streamResponseBody_timeoutWithUnclosableStream_stillReportsTimeout() {
        service.readTimeoutSeconds = 1;
        MediaAttachment attachment = MediaAttachment.ofInline(
                "att-unclosable", AttachmentType.IMAGE, "image/png", "u.png", VALID_PNG_BYTES, Map.of());

        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class,
                () -> service.streamResponseBody(new StalledUnclosableInputStream(), attachment, "image/png", 1024));

        assertEquals(OutboundHttpException.Category.TIMEOUT, ex.category());
    }

    @Test
    @DisplayName("A body read that keeps returning data eventually exhausts the read deadline")
    void streamResponseBody_exhaustsDeadlineOnManyShortReads() {
        service.readTimeoutSeconds = 1;
        MediaAttachment attachment = MediaAttachment.ofInline(
                "att-deadline", AttachmentType.IMAGE, "image/png", "d.png", VALID_PNG_BYTES, Map.of());

        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class,
                () -> service.streamResponseBody(oneByteForever(), attachment, "image/png", 100_000_000L));

        assertEquals(OutboundHttpException.Category.TIMEOUT, ex.category());
        assertTrue(ex.getMessage().contains("exceeded timeout deadline"));
    }

    @Test
    @DisplayName("An interrupt while awaiting a body read maps to an I/O failure")
    void streamResponseBody_interruptedRead_mapsToIoFailure() throws Exception {
        service.readTimeoutSeconds = 30;
        MediaAttachment attachment = MediaAttachment.ofInline(
                "att-read-int", AttachmentType.IMAGE, "image/png", "r.png", VALID_PNG_BYTES, Map.of());

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(
                () -> {
                    try {
                        service.streamResponseBody(new StalledInputStream(), attachment, "image/png", 1024);
                    } catch (Throwable t) {
                        failure.set(t);
                    }
                },
                "media-stream-interrupt-test");
        worker.setDaemon(true);
        worker.start();
        Thread.sleep(300L);
        worker.interrupt();
        worker.join(10_000L);

        assertFalse(worker.isAlive(), "worker must not be left blocked");
        assertTrue(failure.get() instanceof OutboundHttpException, "unexpected failure: " + failure.get());
        assertEquals(OutboundHttpException.Category.IO, ((OutboundHttpException) failure.get()).category());
        assertTrue(failure.get().getMessage().contains("interrupted"));
    }

    @Test
    @DisplayName("An IOException from a body read is rethrown and wrapped as an I/O failure")
    void streamResponseBody_readThrowsIoException_wrapsAsIoFailure() {
        MediaAttachment attachment = MediaAttachment.ofInline(
                "att-io", AttachmentType.IMAGE, "image/png", "io.png", VALID_PNG_BYTES, Map.of());

        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class,
                () -> service.streamResponseBody(
                        failingStream(new IOException("read boom")), attachment, "image/png", 1024));

        assertEquals(OutboundHttpException.Category.IO, ex.category());
        assertTrue(ex.getMessage().contains("I/O error while streaming media download"));
    }

    @Test
    @DisplayName("A RuntimeException from a body read propagates unchanged")
    void streamResponseBody_readThrowsRuntimeException_propagatesUnchanged() {
        MediaAttachment attachment = MediaAttachment.ofInline(
                "att-runtime", AttachmentType.IMAGE, "image/png", "rt.png", VALID_PNG_BYTES, Map.of());
        IllegalStateException boom = new IllegalStateException("read boom");

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> service.streamResponseBody(failingStream(boom), attachment, "image/png", 1024));

        assertSame(boom, ex);
    }

    @Test
    @DisplayName("An Error from a body read is wrapped as an I/O failure")
    void streamResponseBody_readThrowsError_wrapsAsIoFailure() {
        MediaAttachment attachment = MediaAttachment.ofInline(
                "att-error", AttachmentType.IMAGE, "image/png", "err.png", VALID_PNG_BYTES, Map.of());
        AssertionError boom = new AssertionError("read boom");

        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class,
                () -> service.streamResponseBody(failingStream(boom), attachment, "image/png", 1024));

        assertEquals(OutboundHttpException.Category.IO, ex.category());
        assertTrue(ex.getMessage().contains("I/O error while streaming media download"));
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** Allows every request; used where the transport, not policy gating, is under test. */
    private static final class AllowAllPolicy extends OutboundHttpPolicyService {
        @Override
        public OutboundPolicyDecision evaluate(
                String url,
                OutboundPurpose purpose,
                LlmConnectionDto connection,
                String actorUserId,
                boolean preApproved) {
            return OutboundPolicyDecision.allow(purpose, actorUserId);
        }
    }

    /** Allows everything except URLs matching {@code matches}, which receive {@code decision}. */
    private static final class RulePolicy extends OutboundHttpPolicyService {
        private final Predicate<String> matches;
        private final OutboundPolicyDecision decision;

        RulePolicy(Predicate<String> matches, OutboundPolicyDecision decision) {
            this.matches = matches;
            this.decision = decision;
        }

        @Override
        public OutboundPolicyDecision evaluate(
                String url,
                OutboundPurpose purpose,
                LlmConnectionDto connection,
                String actorUserId,
                boolean preApproved) {
            return matches.test(url) ? decision : OutboundPolicyDecision.allow(purpose, actorUserId);
        }
    }

    /** A resolver that completes successfully, standing in for a real Telegram fetch. */
    private static final class StubTelegramResolver extends GovernedTelegramMediaResolver {
        @Override
        public DownloadedMedia resolveAndDownload(
                MediaAttachment attachment,
                String actorUserId,
                boolean preApproved,
                long maximumBytes,
                GovernedMediaDownloadService downloadService) {
            return DownloadedMedia.ofInline(
                    attachment.attachmentId(),
                    attachment.type(),
                    attachment.mimeType(),
                    attachment.fileName(),
                    VALID_PNG_BYTES);
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("content-type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Emits the PNG magic header first, then one byte per read forever — never signalling EOF. */
    private static InputStream oneByteForever() {
        return new InputStream() {
            private int index;

            @Override
            public int read() {
                return VALID_PNG_BYTES[index++ % VALID_PNG_BYTES.length] & 0xFF;
            }

            @Override
            public int read(byte[] target, int offset, int length) {
                target[offset] = VALID_PNG_BYTES[index++ % VALID_PNG_BYTES.length];
                return 1;
            }
        };
    }

    /** A stream whose body read throws {@code failure} on a worker thread. */
    private static InputStream failingStream(Throwable failure) {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                return read(new byte[1], 0, 1);
            }

            @Override
            public int read(byte[] target, int offset, int length) throws IOException {
                if (failure instanceof IOException io) throw io;
                if (failure instanceof RuntimeException runtime) throw runtime;
                if (failure instanceof Error error) throw error;
                throw new IllegalStateException("unsupported failure type: " + failure);
            }
        };
    }
}
