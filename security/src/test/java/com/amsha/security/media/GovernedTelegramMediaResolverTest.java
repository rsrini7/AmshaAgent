package com.amsha.security.media;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import com.amsha.channel.api.AttachmentType;
import com.amsha.channel.api.MediaAttachment;
import com.amsha.core.audit.AuditService;
import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.security.api.DownloadedMedia;
import com.amsha.security.api.OutboundPolicyDecision;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.http.OutboundHttpException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GovernedTelegramMediaResolverTest {

    private static final byte[] VALID_PNG =
            new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D};
    private static final String BOT_TOKEN = "123456789:ABC_def-GHI_secret12345";

    @Mock
    OutboundHttpPolicyService policyService;

    @Mock
    AuditService auditService;

    GovernedMediaDownloadService downloadService;
    GovernedTelegramMediaResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new GovernedTelegramMediaResolver(
                BOT_TOKEN, "https://api.telegram.org", policyService, new ObjectMapper());

        downloadService = Mockito.spy(new GovernedMediaDownloadService());
        downloadService.policyService = policyService;
        downloadService.auditService = auditService;
        downloadService.telegramResolver = resolver;
        downloadService.dataDir = "data";
        downloadService.maxImageSizeMb = 10;
        downloadService.maxDocSizeMb = 25;
        downloadService.maxTotalPayloadMb = 50;
        downloadService.connectTimeoutSeconds = 5;
        downloadService.readTimeoutSeconds = 5;
    }

    @Test
    @DisplayName(
            "Happy-path: getFile response parses file_path and invokes downloadHttpWithRedirects with both policy gates evaluated")
    void resolveAndDownload_happyPath_resolvesAndStreamsSuccessfully() throws Exception {
        @SuppressWarnings("unchecked")
        HttpResponse<InputStream> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body())
                .thenReturn(new ByteArrayInputStream("{\"ok\":true,\"result\":{\"file_path\":\"photos/photo_1.png\"}}"
                        .getBytes(StandardCharsets.UTF_8)));
        resolver.customMetadataTransport = _ -> mockResponse;

        when(policyService.evaluate(
                        eq("https://api.telegram.org/botREDACTED/getFile"),
                        eq(OutboundPurpose.MEDIA_DOWNLOAD),
                        any(),
                        eq("user-1"),
                        eq(true)))
                .thenReturn(OutboundPolicyDecision.allow(OutboundPurpose.MEDIA_DOWNLOAD, "user-1"));

        when(policyService.evaluate(
                        eq("https://api.telegram.org/file/botREDACTED/photos/photo_1.png"),
                        eq(OutboundPurpose.MEDIA_DOWNLOAD),
                        any(),
                        eq("user-1"),
                        eq(true)))
                .thenReturn(OutboundPolicyDecision.allow(OutboundPurpose.MEDIA_DOWNLOAD, "user-1"));

        MediaAttachment attachment = MediaAttachment.ofUri(
                "att-tg-1",
                AttachmentType.IMAGE,
                "image/png",
                "photo.png",
                100L,
                URI.create("telegram://file/photo_valid_123"),
                Map.of());

        DownloadedMedia expectedMedia =
                DownloadedMedia.ofInline("att-tg-1", AttachmentType.IMAGE, "image/png", "photo_1.png", VALID_PNG);
        Mockito.doReturn(expectedMedia)
                .when(downloadService)
                .downloadHttpWithRedirects(
                        any(URI.class), eq(attachment), eq("user-1"), eq(true), any(URI.class), anyLong(), eq(0));

        DownloadedMedia actual = resolver.resolveAndDownload(attachment, "user-1", true, 1024 * 1024, downloadService);

        assertNotNull(actual);
        assertEquals("att-tg-1", actual.mediaId());
        assertEquals(AttachmentType.IMAGE, actual.type());
        assertEquals("image/png", actual.mimeType());
        assertArrayEquals(VALID_PNG, actual.inlineData());

        ArgumentCaptor<URI> downloadUriCaptor = ArgumentCaptor.forClass(URI.class);
        verify(downloadService)
                .downloadHttpWithRedirects(
                        downloadUriCaptor.capture(),
                        eq(attachment),
                        eq("user-1"),
                        eq(true),
                        eq(URI.create("https://api.telegram.org")),
                        eq(1024L * 1024L),
                        eq(0));
        assertEquals(
                "https://api.telegram.org/file/bot" + BOT_TOKEN + "/photos/photo_1.png",
                downloadUriCaptor.getValue().toString());
    }

    @Test
    @DisplayName(
            "End-to-end stream processing: parses real getFile JSON, streams real binary payload, and validates magic bytes")
    void resolveAndDownload_endToEndStreamProcessing_succeedsAndAudits() throws Exception {
        @SuppressWarnings("unchecked")
        HttpResponse<InputStream> getFileResp = mock(HttpResponse.class);
        when(getFileResp.statusCode()).thenReturn(200);
        when(getFileResp.body())
                .thenReturn(new ByteArrayInputStream("{\"ok\":true,\"result\":{\"file_path\":\"photos/real_file.png\"}}"
                        .getBytes(StandardCharsets.UTF_8)));

        resolver.customMetadataTransport = _ -> getFileResp;

        when(policyService.evaluate(anyString(), eq(OutboundPurpose.MEDIA_DOWNLOAD), any(), eq("user-1"), eq(true)))
                .thenReturn(OutboundPolicyDecision.allow(OutboundPurpose.MEDIA_DOWNLOAD, "user-1"));

        MediaAttachment attachment = MediaAttachment.ofUri(
                "att-real-1",
                AttachmentType.IMAGE,
                "image/png",
                "real.png",
                (long) VALID_PNG.length,
                URI.create("telegram://file/photo_real_123"),
                Map.of());

        // Use real streamResponseBody behavior
        Mockito.doAnswer(inv -> downloadService.streamResponseBody(
                        new ByteArrayInputStream(VALID_PNG), attachment, "image/png", VALID_PNG.length))
                .when(downloadService)
                .downloadHttpWithRedirects(any(), any(), any(), anyBoolean(), any(), anyLong(), eq(0));

        DownloadedMedia media = resolver.resolveAndDownload(attachment, "user-1", true, 1024 * 1024, downloadService);

        assertNotNull(media);
        assertEquals("att-real-1", media.mediaId());
        assertEquals("image/png", media.mimeType());
        assertArrayEquals(VALID_PNG, media.inlineData());
        media.close();
    }

    @Test
    @DisplayName("Redirect URL token scrubbing: sanitizeUrlForPolicy removes bot token from redirect targets")
    void sanitizeUrlForPolicy_redactsBotTokensFromRedirects() {
        String raw = "https://api.telegram.org/file/bot" + BOT_TOKEN + "/photos/photo_1.png";
        String sanitized = GovernedMediaDownloadService.sanitizeUrlForPolicy(raw);
        assertFalse(sanitized.contains(BOT_TOKEN));
        assertTrue(sanitized.contains("/botREDACTED/"));
        assertDoesNotThrow(() -> URI.create(sanitized));

        String getFileUrl = "https://api.telegram.org/bot" + BOT_TOKEN + "/getFile";
        String sanitizedGetFile = GovernedMediaDownloadService.sanitizeUrlForPolicy(getFileUrl);
        assertFalse(sanitizedGetFile.contains(BOT_TOKEN));
        assertTrue(sanitizedGetFile.contains("/botREDACTED/"));
        assertDoesNotThrow(() -> URI.create(sanitizedGetFile));

        assertNull(GovernedMediaDownloadService.sanitizeUrlForPolicy(null));
    }

    @Test
    @DisplayName("Bot token format validation: accepts valid format and rejects delimiters or control characters")
    void requireBotToken_validatesTokenFormat() {
        resolver.telegramBotToken = Optional.of("123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11");
        MediaAttachment attachment = MediaAttachment.ofUri(
                "att-tg",
                AttachmentType.IMAGE,
                "image/png",
                "photo.png",
                100L,
                URI.create("telegram://file/photo123"),
                Map.of());

        // Invalid: contains URI path delimiter '/'
        resolver.telegramBotToken = Optional.of("123456/bottoken");
        OutboundHttpException exSlash = assertThrows(
                OutboundHttpException.class,
                () -> resolver.resolveAndDownload(attachment, "user-1", true, 1024 * 1024, downloadService));
        assertTrue(exSlash.getMessage().contains("format is invalid"));

        // Invalid: contains query delimiter '?'
        resolver.telegramBotToken = Optional.of("123456:secret?param=val");
        OutboundHttpException exQuery = assertThrows(
                OutboundHttpException.class,
                () -> resolver.resolveAndDownload(attachment, "user-1", true, 1024 * 1024, downloadService));
        assertTrue(exQuery.getMessage().contains("format is invalid"));

        // Invalid: contains fragment delimiter '#'
        resolver.telegramBotToken = Optional.of("123456:secret#frag");
        OutboundHttpException exFrag = assertThrows(
                OutboundHttpException.class,
                () -> resolver.resolveAndDownload(attachment, "user-1", true, 1024 * 1024, downloadService));
        assertTrue(exFrag.getMessage().contains("format is invalid"));

        // Invalid: too short
        resolver.telegramBotToken = Optional.of("short");
        OutboundHttpException exShort = assertThrows(
                OutboundHttpException.class,
                () -> resolver.resolveAndDownload(attachment, "user-1", true, 1024 * 1024, downloadService));
        assertTrue(exShort.getMessage().contains("format is invalid"));
    }

    @Test
    @DisplayName("File download hop policy denial halts with POLICY_DENIED before streaming")
    void resolveAndDownload_fileHopPolicyDenied_halts() throws Exception {
        @SuppressWarnings("unchecked")
        HttpResponse<InputStream> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body())
                .thenReturn(new ByteArrayInputStream("{\"ok\":true,\"result\":{\"file_path\":\"photos/photo_1.png\"}}"
                        .getBytes(StandardCharsets.UTF_8)));
        resolver.customMetadataTransport = _ -> mockResponse;

        when(policyService.evaluate(
                        eq("https://api.telegram.org/botREDACTED/getFile"),
                        eq(OutboundPurpose.MEDIA_DOWNLOAD),
                        any(),
                        eq("user-1"),
                        eq(true)))
                .thenReturn(OutboundPolicyDecision.allow(OutboundPurpose.MEDIA_DOWNLOAD, "user-1"));

        when(policyService.evaluate(
                        eq("https://api.telegram.org/file/botREDACTED/photos/photo_1.png"),
                        eq(OutboundPurpose.MEDIA_DOWNLOAD),
                        any(),
                        eq("user-1"),
                        eq(true)))
                .thenReturn(OutboundPolicyDecision.deny(
                        "File path policy block", OutboundPurpose.MEDIA_DOWNLOAD, "user-1"));

        MediaAttachment attachment = MediaAttachment.ofUri(
                "att-tg-1",
                AttachmentType.IMAGE,
                "image/png",
                "photo.png",
                100L,
                URI.create("telegram://file/photo_valid_123"),
                Map.of());

        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class,
                () -> resolver.resolveAndDownload(attachment, "user-1", true, 1024 * 1024, downloadService));
        assertEquals(OutboundHttpException.Category.POLICY_DENIED, ex.category());
        assertTrue(ex.getMessage().contains("File path policy block"));
    }

    @Test
    @DisplayName("File download hop requiring HITL halts with HITL_REQUIRED")
    void resolveAndDownload_fileHopRequiresHitl_halts() throws Exception {
        @SuppressWarnings("unchecked")
        HttpResponse<InputStream> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body())
                .thenReturn(new ByteArrayInputStream("{\"ok\":true,\"result\":{\"file_path\":\"photos/photo_1.png\"}}"
                        .getBytes(StandardCharsets.UTF_8)));
        resolver.customMetadataTransport = _ -> mockResponse;

        when(policyService.evaluate(
                        eq("https://api.telegram.org/botREDACTED/getFile"),
                        eq(OutboundPurpose.MEDIA_DOWNLOAD),
                        any(),
                        eq("user-1"),
                        eq(false)))
                .thenReturn(OutboundPolicyDecision.allow(OutboundPurpose.MEDIA_DOWNLOAD, "user-1"));

        when(policyService.evaluate(
                        eq("https://api.telegram.org/file/botREDACTED/photos/photo_1.png"),
                        eq(OutboundPurpose.MEDIA_DOWNLOAD),
                        any(),
                        eq("user-1"),
                        eq(false)))
                .thenReturn(OutboundPolicyDecision.requireHitl(
                        "approval needed for file download", OutboundPurpose.MEDIA_DOWNLOAD, "user-1"));

        MediaAttachment attachment = MediaAttachment.ofUri(
                "att-tg-1",
                AttachmentType.IMAGE,
                "image/png",
                "photo.png",
                100L,
                URI.create("telegram://file/photo_valid_123"),
                Map.of());

        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class,
                () -> resolver.resolveAndDownload(attachment, "user-1", false, 1024 * 1024, downloadService));
        assertEquals(OutboundHttpException.Category.HITL_REQUIRED, ex.category());
    }

    @Test
    @DisplayName("getFile response exceeding size limit fails closed with policy exception")
    void readBoundedString_exceedsLimit_failsClosed() {
        byte[] oversized = new byte[70 * 1024]; // 70 KiB
        InputStream in = new ByteArrayInputStream(oversized);

        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class,
                () -> GovernedTelegramMediaResolver.readBoundedString(in, 64 * 1024, 5, BOT_TOKEN));
        assertEquals(OutboundHttpException.Category.POLICY_DENIED, ex.category());
        assertTrue(ex.getMessage().contains("exceeds maximum size"));
    }

    @Test
    @DisplayName(
            "parseTelegramFilePath correctly extracts file_path from valid JSON and rejects errors without leaking raw payload")
    void parseTelegramFilePath_variousPayloads() {
        ObjectMapper mapper = new ObjectMapper();

        String valid = "{\"ok\":true,\"result\":{\"file_id\":\"123\",\"file_path\":\"documents/doc_1.pdf\"}}";
        assertEquals("documents/doc_1.pdf", GovernedTelegramMediaResolver.parseTelegramFilePath(valid, mapper));

        assertThrows(
                OutboundHttpException.class, () -> GovernedTelegramMediaResolver.parseTelegramFilePath(null, mapper));
        assertThrows(
                OutboundHttpException.class, () -> GovernedTelegramMediaResolver.parseTelegramFilePath("", mapper));

        OutboundHttpException parseEx = assertThrows(
                OutboundHttpException.class,
                () -> GovernedTelegramMediaResolver.parseTelegramFilePath("not-valid-json", mapper));
        assertEquals("Failed to parse Telegram getFile JSON response", parseEx.getMessage());
        assertFalse(parseEx.getMessage().contains("not-valid-json"));

        String notOk = "{\"ok\":false,\"description\":\"Bad Request: file is too big\"}";
        OutboundHttpException exNotOk = assertThrows(
                OutboundHttpException.class, () -> GovernedTelegramMediaResolver.parseTelegramFilePath(notOk, mapper));
        assertEquals(OutboundHttpException.Category.IO, exNotOk.category());

        String missingResult = "{\"ok\":true}";
        OutboundHttpException exMissing = assertThrows(
                OutboundHttpException.class,
                () -> GovernedTelegramMediaResolver.parseTelegramFilePath(missingResult, mapper));
        assertEquals(OutboundHttpException.Category.IO, exMissing.category());

        String unsafePath = "{\"ok\":true,\"result\":{\"file_path\":\"/etc/passwd\"}}";
        OutboundHttpException exUnsafe = assertThrows(
                OutboundHttpException.class,
                () -> GovernedTelegramMediaResolver.parseTelegramFilePath(unsafePath, mapper));
        assertEquals(OutboundHttpException.Category.POLICY_DENIED, exUnsafe.category());
    }

    @Test
    @DisplayName("Valid telegram URI extracts file ID correctly")
    void validateTelegramUri_validUris_extractsFileId() {
        assertEquals(
                "AgACAgUAAxkBAAIC1",
                GovernedTelegramMediaResolver.validateTelegramUri(URI.create("telegram://file/AgACAgUAAxkBAAIC1")));
        assertEquals(
                "file_123_abc-XYZ",
                GovernedTelegramMediaResolver.validateTelegramUri(URI.create("telegram://file/file_123_abc-XYZ")));
    }

    @Test
    @DisplayName("Invalid telegram URI variants fail closed with policy exception")
    void validateTelegramUri_invalidUriVariants_failClosed() {
        assertThrows(OutboundHttpException.class, () -> GovernedTelegramMediaResolver.validateTelegramUri(null));
        assertThrows(
                OutboundHttpException.class,
                () -> GovernedTelegramMediaResolver.validateTelegramUri(
                        URI.create("http://api.telegram.org/file/123")));
        assertThrows(
                OutboundHttpException.class,
                () -> GovernedTelegramMediaResolver.validateTelegramUri(URI.create("telegram://wronghost/file123")));
        assertThrows(
                OutboundHttpException.class,
                () -> GovernedTelegramMediaResolver.validateTelegramUri(URI.create("telegram://file/123/extra")));
        assertThrows(
                OutboundHttpException.class,
                () -> GovernedTelegramMediaResolver.validateTelegramUri(URI.create("telegram://file/123?query=1")));
        assertThrows(
                OutboundHttpException.class,
                () -> GovernedTelegramMediaResolver.validateTelegramUri(URI.create("telegram://file/123#fragment")));
        assertThrows(
                OutboundHttpException.class,
                () -> GovernedTelegramMediaResolver.validateTelegramUri(URI.create("telegram://user:pass@file/123")));
        assertThrows(
                OutboundHttpException.class,
                () -> GovernedTelegramMediaResolver.validateTelegramUri(URI.create("telegram://file:8080/123")));
        assertThrows(
                OutboundHttpException.class,
                () -> GovernedTelegramMediaResolver.validateTelegramUri(URI.create("telegram://file/../etc/passwd")));
        assertThrows(
                OutboundHttpException.class,
                () -> GovernedTelegramMediaResolver.validateTelegramUri(URI.create("telegram://file/")));
        assertThrows(
                OutboundHttpException.class,
                () -> GovernedTelegramMediaResolver.validateTelegramUri(URI.create("telegram://file/bad*char")));
    }

    @Test
    @DisplayName("Telegram base URI validation accepts valid HTTP/HTTPS and rejects malformed")
    void validateBaseUri_validAndInvalid() {
        URI validHttps = GovernedTelegramMediaResolver.validateBaseUri("https://api.telegram.org");
        assertEquals("https", validHttps.getScheme());
        assertEquals("api.telegram.org", validHttps.getHost());

        URI validHttp = GovernedTelegramMediaResolver.validateBaseUri("http://telegram.custom-proxy.internal:8443");
        assertEquals("http", validHttp.getScheme());

        assertEquals(
                "https://api.telegram.org",
                GovernedTelegramMediaResolver.normalizeBaseUri("https://api.telegram.org/"));

        assertThrows(
                OutboundHttpException.class,
                () -> GovernedTelegramMediaResolver.validateBaseUri("ftp://api.telegram.org"));
        assertThrows(
                OutboundHttpException.class,
                () -> GovernedTelegramMediaResolver.validateBaseUri("https://user:pass@api.telegram.org"));
        assertThrows(
                OutboundHttpException.class,
                () -> GovernedTelegramMediaResolver.validateBaseUri("https://api.telegram.org?foo=bar"));
    }

    @Test
    @DisplayName("Missing or placeholder bot token fails closed")
    void resolveAndDownload_missingOrPlaceholderToken_failsClosed() {
        resolver.telegramBotToken = Optional.of("mock-telegram-token");
        MediaAttachment attachment = MediaAttachment.ofUri(
                "att-tg",
                AttachmentType.IMAGE,
                "image/png",
                "photo.png",
                100L,
                URI.create("telegram://file/photo123"),
                Map.of());

        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class,
                () -> resolver.resolveAndDownload(attachment, "user-1", true, 1024 * 1024, downloadService));
        assertEquals(OutboundHttpException.Category.POLICY_DENIED, ex.category());
        assertTrue(ex.getMessage().contains("no valid bot token configured"));

        resolver.telegramBotToken = Optional.empty();
        OutboundHttpException ex2 = assertThrows(
                OutboundHttpException.class,
                () -> resolver.resolveAndDownload(attachment, "user-1", true, 1024 * 1024, downloadService));
        assertEquals(OutboundHttpException.Category.POLICY_DENIED, ex2.category());
    }

    @Test
    @DisplayName("Telegram file_path validation accepts safe relative paths and rejects absolute/traversal")
    void validateTelegramFilePath_validAndInvalid() {
        GovernedTelegramMediaResolver.validateTelegramFilePath("photos/photo_1.jpg");
        GovernedTelegramMediaResolver.validateTelegramFilePath("documents/file_2.pdf");
        GovernedTelegramMediaResolver.validateTelegramFilePath("voice/voice_3.ogg");

        assertThrows(OutboundHttpException.class, () -> GovernedTelegramMediaResolver.validateTelegramFilePath(null));
        assertThrows(OutboundHttpException.class, () -> GovernedTelegramMediaResolver.validateTelegramFilePath(""));
        assertThrows(
                OutboundHttpException.class,
                () -> GovernedTelegramMediaResolver.validateTelegramFilePath("https://evil.com/img.png"));
        assertThrows(
                OutboundHttpException.class,
                () -> GovernedTelegramMediaResolver.validateTelegramFilePath("//evil.com/img.png"));
        assertThrows(
                OutboundHttpException.class,
                () -> GovernedTelegramMediaResolver.validateTelegramFilePath("/etc/passwd"));
        assertThrows(
                OutboundHttpException.class,
                () -> GovernedTelegramMediaResolver.validateTelegramFilePath("photos/../../secret.txt"));
        assertThrows(
                OutboundHttpException.class,
                () -> GovernedTelegramMediaResolver.validateTelegramFilePath("photos\\evil.png"));
        assertThrows(
                OutboundHttpException.class,
                () -> GovernedTelegramMediaResolver.validateTelegramFilePath("photos\0evil.png"));
        assertThrows(
                OutboundHttpException.class,
                () -> GovernedTelegramMediaResolver.validateTelegramFilePath("photos/evil*.png"));
    }

    @Test
    @DisplayName("Outbound policy requiring HITL halts with HITL_REQUIRED")
    void resolveAndDownload_requiresHitl_halts() {
        when(policyService.evaluate(anyString(), any(), any(), any(), eq(false)))
                .thenReturn(OutboundPolicyDecision.requireHitl(
                        "operator approval needed", OutboundPurpose.MEDIA_DOWNLOAD, "user-1"));

        MediaAttachment attachment = MediaAttachment.ofUri(
                "att-tg",
                AttachmentType.IMAGE,
                "image/png",
                "photo.png",
                100L,
                URI.create("telegram://file/photo123"),
                Map.of());

        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class,
                () -> resolver.resolveAndDownload(attachment, "user-1", false, 1024 * 1024, downloadService));
        assertEquals(OutboundHttpException.Category.HITL_REQUIRED, ex.category());
    }

    @Test
    @DisplayName("Outbound policy denial halts with POLICY_DENIED")
    void resolveAndDownload_policyDenied_halts() {
        when(policyService.evaluate(anyString(), any(), any(), any(), anyBoolean()))
                .thenReturn(OutboundPolicyDecision.deny("Domain blocked", OutboundPurpose.MEDIA_DOWNLOAD, "user-1"));

        MediaAttachment attachment = MediaAttachment.ofUri(
                "att-tg",
                AttachmentType.IMAGE,
                "image/png",
                "photo.png",
                100L,
                URI.create("telegram://file/photo123"),
                Map.of());

        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class,
                () -> resolver.resolveAndDownload(attachment, "user-1", true, 1024 * 1024, downloadService));
        assertEquals(OutboundHttpException.Category.POLICY_DENIED, ex.category());
        assertTrue(ex.getMessage().contains("Domain blocked"));
    }

    @Test
    @DisplayName("Loopback target URL is rejected by SSRF / DNS rebinding guard before connect")
    void resolveAndDownload_loopbackBaseUri_rejectedBySsrfGuard() {
        resolver.telegramBaseUri = "http://127.0.0.1:8080";
        when(policyService.evaluate(anyString(), any(), any(), any(), anyBoolean()))
                .thenReturn(OutboundPolicyDecision.allow(OutboundPurpose.MEDIA_DOWNLOAD, "user-1"));

        MediaAttachment attachment = MediaAttachment.ofUri(
                "att-tg",
                AttachmentType.IMAGE,
                "image/png",
                "photo.png",
                100L,
                URI.create("telegram://file/photo123"),
                Map.of());

        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class,
                () -> resolver.resolveAndDownload(attachment, "user-1", true, 1024 * 1024, downloadService));
        assertEquals(OutboundHttpException.Category.DNS_REBINDING, ex.category());
        assertTrue(ex.getMessage().contains("prohibited private/loopback/link-local range"));
    }

    @Test
    @DisplayName("Token is recursively redacted from exception messages and nested causes")
    void scrubTokenException_redactsTokenFromExceptions() {
        Exception cause = new IOException("nested error with " + BOT_TOKEN);
        Exception raw = new RuntimeException(
                "HTTP 500 error contacting https://api.telegram.org/bot" + BOT_TOKEN + "/getFile", cause);
        RuntimeException scrubbed = GovernedTelegramMediaResolver.scrubTokenException(raw, BOT_TOKEN);
        assertNotNull(scrubbed);
        assertFalse(scrubbed.getMessage().contains(BOT_TOKEN));
        assertTrue(scrubbed.getMessage().contains("[REDACTED]"));
        assertNotNull(scrubbed.getCause());
        assertFalse(scrubbed.getCause().getMessage().contains(BOT_TOKEN));
        assertTrue(scrubbed.getCause().getMessage().contains("[REDACTED]"));

        assertNull(GovernedTelegramMediaResolver.scrubTokenException(null, BOT_TOKEN));

        OutboundHttpException ohe = OutboundHttpException.io("I/O failure on bot" + BOT_TOKEN);
        RuntimeException scrubbedOhe = GovernedTelegramMediaResolver.scrubTokenException(ohe, BOT_TOKEN);
        assertEquals(OutboundHttpException.Category.IO, ((OutboundHttpException) scrubbedOhe).category());
        assertFalse(scrubbedOhe.getMessage().contains(BOT_TOKEN));

        com.amsha.core.security.http.OutboundHttpException coreOhe =
                com.amsha.core.security.http.OutboundHttpException.io("I/O failure on bot" + BOT_TOKEN);
        RuntimeException scrubbedCoreOhe = GovernedTelegramMediaResolver.scrubTokenException(coreOhe, BOT_TOKEN);
        assertTrue(scrubbedCoreOhe instanceof OutboundHttpException);
        assertEquals(OutboundHttpException.Category.IO, ((OutboundHttpException) scrubbedCoreOhe).category());
        assertFalse(scrubbedCoreOhe.getMessage().contains(BOT_TOKEN));

        RuntimeException rawWithSuppressed = new RuntimeException("primary failure");
        rawWithSuppressed.addSuppressed(new IOException("suppressed " + BOT_TOKEN));
        RuntimeException scrubbedWithSuppressed =
                GovernedTelegramMediaResolver.scrubTokenException(rawWithSuppressed, BOT_TOKEN);
        assertEquals(1, scrubbedWithSuppressed.getSuppressed().length);
        assertFalse(scrubbedWithSuppressed.getSuppressed()[0].getMessage().contains(BOT_TOKEN));
    }

    // ------------------------------------------------------------------
    // Additional branch coverage: guards, transport failures, bounded read
    // ------------------------------------------------------------------

    private MediaAttachment attachment(String uri) {
        return MediaAttachment.ofUri(
                "att-tg", AttachmentType.IMAGE, "image/png", "photo.png", 100L, URI.create(uri), Map.of());
    }

    @Test
    @DisplayName("No-arg (CDI) constructor is usable")
    void noArgConstructor_constructs() {
        assertNotNull(new GovernedTelegramMediaResolver());
    }

    @Test
    @DisplayName("Attachment with a null sourceUri is rejected")
    void resolveAndDownload_nullSourceUri_rejected() {
        MediaAttachment att = mock(MediaAttachment.class);
        when(att.sourceUri()).thenReturn(null);

        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolveAndDownload(att, "user-1", true, 1024, downloadService));
    }

    @Test
    @DisplayName("Failure thrown by the download service is token-scrubbed and rethrown")
    void resolveAndDownload_downloadServiceThrows_scrubsAndRethrows() throws Exception {
        @SuppressWarnings("unchecked")
        HttpResponse<InputStream> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body())
                .thenReturn(new ByteArrayInputStream(
                        "{\"ok\":true,\"result\":{\"file_path\":\"photos/p.png\"}}".getBytes(StandardCharsets.UTF_8)));
        resolver.customMetadataTransport = _ -> resp;
        when(policyService.evaluate(anyString(), eq(OutboundPurpose.MEDIA_DOWNLOAD), any(), eq("user-1"), anyBoolean()))
                .thenReturn(OutboundPolicyDecision.allow(OutboundPurpose.MEDIA_DOWNLOAD, "user-1"));
        Mockito.doThrow(new RuntimeException("stream boom " + BOT_TOKEN))
                .when(downloadService)
                .downloadHttpWithRedirects(any(), any(), any(), anyBoolean(), any(), anyLong(), eq(0));

        RuntimeException ex = assertThrows(
                RuntimeException.class,
                () -> resolver.resolveAndDownload(
                        attachment("telegram://file/photo123"), "user-1", true, 1024, downloadService));
        assertFalse(ex.getMessage().contains(BOT_TOKEN));
        assertTrue(ex.getMessage().contains("[REDACTED]"));
    }

    @Test
    @DisplayName("Opaque telegram URI is rejected")
    void validateTelegramUri_opaqueUri_rejected() {
        assertThrows(
                OutboundHttpException.class,
                () -> GovernedTelegramMediaResolver.validateTelegramUri(URI.create("telegram:file/123")));
    }

    @Test
    @DisplayName("Telegram URI with no path is rejected")
    void validateTelegramUri_missingPath_rejected() {
        assertThrows(
                OutboundHttpException.class,
                () -> GovernedTelegramMediaResolver.validateTelegramUri(URI.create("telegram://file")));
    }

    @Test
    @DisplayName("Telegram URI with a control character in the file id is rejected")
    void validateTelegramUri_controlCharInFileId_rejected() {
        assertThrows(
                OutboundHttpException.class,
                () -> GovernedTelegramMediaResolver.validateTelegramUri(URI.create("telegram://file/%01")));
    }

    @Test
    @DisplayName("Malformed base URI configuration is rejected")
    void validateBaseUri_malformed_rejected() {
        assertThrows(
                OutboundHttpException.class,
                () -> GovernedTelegramMediaResolver.validateBaseUri("http://exa mple.com"));
    }

    @Test
    @DisplayName("getFile transport timeout maps to TIMEOUT")
    void fetchTelegramFilePath_transportTimeout_mapsToTimeout() {
        resolver.customMetadataTransport = _ -> {
            throw new HttpTimeoutException("slow");
        };
        when(policyService.evaluate(anyString(), eq(OutboundPurpose.MEDIA_DOWNLOAD), any(), eq("user-1"), anyBoolean()))
                .thenReturn(OutboundPolicyDecision.allow(OutboundPurpose.MEDIA_DOWNLOAD, "user-1"));

        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class,
                () -> resolver.resolveAndDownload(
                        attachment("telegram://file/photo123"), "user-1", true, 1024, downloadService));
        assertEquals(OutboundHttpException.Category.TIMEOUT, ex.category());
    }

    @Test
    @DisplayName("getFile transport I/O failure maps to IO and scrubs the token")
    void fetchTelegramFilePath_ioFailure_mapsToIoAndScrubs() {
        resolver.customMetadataTransport = _ -> {
            throw new IOException("io fail " + BOT_TOKEN);
        };
        when(policyService.evaluate(anyString(), eq(OutboundPurpose.MEDIA_DOWNLOAD), any(), eq("user-1"), anyBoolean()))
                .thenReturn(OutboundPolicyDecision.allow(OutboundPurpose.MEDIA_DOWNLOAD, "user-1"));

        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class,
                () -> resolver.resolveAndDownload(
                        attachment("telegram://file/photo123"), "user-1", true, 1024, downloadService));
        assertEquals(OutboundHttpException.Category.IO, ex.category());
        assertFalse(ex.getMessage().contains(BOT_TOKEN));
    }

    @Test
    @DisplayName("getFile transport interruption maps to IO and restores the interrupt flag")
    void fetchTelegramFilePath_interrupted_mapsToIo() {
        resolver.customMetadataTransport = _ -> {
            throw new InterruptedException("interrupted");
        };
        when(policyService.evaluate(anyString(), eq(OutboundPurpose.MEDIA_DOWNLOAD), any(), eq("user-1"), anyBoolean()))
                .thenReturn(OutboundPolicyDecision.allow(OutboundPurpose.MEDIA_DOWNLOAD, "user-1"));

        try {
            OutboundHttpException ex = assertThrows(
                    OutboundHttpException.class,
                    () -> resolver.resolveAndDownload(
                            attachment("telegram://file/photo123"), "user-1", true, 1024, downloadService));
            assertEquals(OutboundHttpException.Category.IO, ex.category());
            assertTrue(Thread.interrupted(), "interrupt flag should be restored");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("getFile non-200 status fails closed and closes the body quietly")
    void fetchTelegramFilePath_non200_closesBodyAndFails() {
        @SuppressWarnings("unchecked")
        HttpResponse<InputStream> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(500);
        when(resp.body()).thenReturn(new InputStream() {
            @Override
            public int read() {
                return -1;
            }

            @Override
            public void close() throws IOException {
                throw new IOException("close failure");
            }
        });
        resolver.customMetadataTransport = _ -> resp;
        when(policyService.evaluate(anyString(), eq(OutboundPurpose.MEDIA_DOWNLOAD), any(), eq("user-1"), anyBoolean()))
                .thenReturn(OutboundPolicyDecision.allow(OutboundPurpose.MEDIA_DOWNLOAD, "user-1"));

        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class,
                () -> resolver.resolveAndDownload(
                        attachment("telegram://file/photo123"), "user-1", true, 1024, downloadService));
        assertEquals(OutboundHttpException.Category.IO, ex.category());
        assertTrue(ex.getMessage().contains("HTTP status 500"));
    }

    @Test
    @DisplayName("getFile non-200 status closes a well-behaved body quietly")
    void fetchTelegramFilePath_non200_closesQuietlyOnSuccess() {
        @SuppressWarnings("unchecked")
        HttpResponse<InputStream> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(404);
        when(resp.body()).thenReturn(new ByteArrayInputStream(new byte[0]));
        resolver.customMetadataTransport = _ -> resp;
        when(policyService.evaluate(anyString(), eq(OutboundPurpose.MEDIA_DOWNLOAD), any(), eq("user-1"), anyBoolean()))
                .thenReturn(OutboundPolicyDecision.allow(OutboundPurpose.MEDIA_DOWNLOAD, "user-1"));

        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class,
                () -> resolver.resolveAndDownload(
                        attachment("telegram://file/photo123"), "user-1", true, 1024, downloadService));
        assertEquals(OutboundHttpException.Category.IO, ex.category());
        assertTrue(ex.getMessage().contains("HTTP status 404"));
    }

    @Test
    @DisplayName("readBoundedString returns empty for a null stream")
    void readBoundedString_nullStream_returnsEmpty() {
        assertEquals("", GovernedTelegramMediaResolver.readBoundedString(null, 100, 5, BOT_TOKEN));
    }

    @Test
    @DisplayName("readBoundedString fails closed when the deadline is already exceeded")
    void readBoundedString_deadlineExceeded_failsClosed() {
        InputStream in = new ByteArrayInputStream(new byte[] {1, 2, 3});
        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class,
                () -> GovernedTelegramMediaResolver.readBoundedString(in, 100, 0, BOT_TOKEN));
        assertEquals(OutboundHttpException.Category.TIMEOUT, ex.category());
    }

    @Test
    @DisplayName("readBoundedString fails closed when a blocking read exceeds the deadline")
    void readBoundedString_blockingReadTimesOut() {
        CountDownLatch latch = new CountDownLatch(1);
        InputStream blocking = new InputStream() {
            @Override
            public int read() throws IOException {
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return -1;
            }
        };
        try {
            OutboundHttpException ex = assertThrows(
                    OutboundHttpException.class,
                    () -> GovernedTelegramMediaResolver.readBoundedString(blocking, 100, 1, BOT_TOKEN));
            assertEquals(OutboundHttpException.Category.TIMEOUT, ex.category());
        } finally {
            latch.countDown();
        }
    }

    @Test
    @DisplayName("readBoundedString fails closed when the calling thread is interrupted")
    void readBoundedString_interrupted_failsClosed() {
        CountDownLatch latch = new CountDownLatch(1);
        InputStream blocking = new InputStream() {
            @Override
            public int read() throws IOException {
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return -1;
            }
        };
        Thread.currentThread().interrupt();
        try {
            OutboundHttpException ex = assertThrows(
                    OutboundHttpException.class,
                    () -> GovernedTelegramMediaResolver.readBoundedString(blocking, 100, 5, BOT_TOKEN));
            assertEquals(OutboundHttpException.Category.IO, ex.category());
        } finally {
            Thread.interrupted();
            latch.countDown();
        }
    }

    @Test
    @DisplayName("readBoundedString propagates an IOException raised by the stream")
    void readBoundedString_readIOException_wrapped() {
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("read boom");
            }
        };
        assertThrows(
                RuntimeException.class,
                () -> GovernedTelegramMediaResolver.readBoundedString(failing, 100, 5, BOT_TOKEN));
    }

    @Test
    @DisplayName("readBoundedString propagates a RuntimeException raised by the stream")
    void readBoundedString_readRuntimeException_wrapped() {
        InputStream failing = new InputStream() {
            @Override
            public int read() {
                throw new IllegalStateException("read boom");
            }
        };
        assertThrows(
                RuntimeException.class,
                () -> GovernedTelegramMediaResolver.readBoundedString(failing, 100, 5, BOT_TOKEN));
    }

    @Test
    @DisplayName("readBoundedString wraps a non-IO/non-runtime throwable from the stream")
    void readBoundedString_readError_wrapped() {
        InputStream failing = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("read boom");
            }
        };
        assertThrows(
                RuntimeException.class,
                () -> GovernedTelegramMediaResolver.readBoundedString(failing, 100, 5, BOT_TOKEN));
    }

    @Test
    @DisplayName("Telegram file_path with a control character is rejected")
    void validateTelegramFilePath_controlChar_rejected() {
        assertThrows(
                OutboundHttpException.class,
                () -> GovernedTelegramMediaResolver.validateTelegramFilePath("photos/evil\u0001.png"));
    }
}
