package com.amsha.security.media;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import com.amsha.channel.api.AttachmentType;
import com.amsha.security.http.OutboundHttpException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MediaSecurityValidatorTest {

    @Test
    @DisplayName("validateAddress blocks IPv4 loopback (127.0.0.1)")
    void validateAddress_blocksIpv4Loopback() throws Exception {
        InetAddress addr = InetAddress.getByName("127.0.0.1");
        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class, () -> MediaSecurityValidator.validateAddress(addr, "127.0.0.1"));
        assertEquals(OutboundHttpException.Category.DNS_REBINDING, ex.category());
    }

    @Test
    @DisplayName("validateAddress blocks RFC1918 private IPv4 ranges (10.x, 172.16.x, 192.168.x)")
    void validateAddress_blocksRfc1918() throws Exception {
        for (String ip : new String[] {"10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.1.1"}) {
            InetAddress addr = InetAddress.getByName(ip);
            OutboundHttpException ex =
                    assertThrows(OutboundHttpException.class, () -> MediaSecurityValidator.validateAddress(addr, ip));
            assertEquals(OutboundHttpException.Category.DNS_REBINDING, ex.category());
        }
    }

    @Test
    @DisplayName("validateAddress blocks cloud metadata address (169.254.169.254) and link-local")
    void validateAddress_blocksMetadataAndLinkLocal() throws Exception {
        InetAddress addr = InetAddress.getByName("169.254.169.254");
        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class, () -> MediaSecurityValidator.validateAddress(addr, "169.254.169.254"));
        assertEquals(OutboundHttpException.Category.DNS_REBINDING, ex.category());
    }

    @Test
    @DisplayName("validateAddress blocks CGNAT (100.64.0.0/10)")
    void validateAddress_blocksCgnat() throws Exception {
        InetAddress addr = InetAddress.getByName("100.64.0.1");
        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class, () -> MediaSecurityValidator.validateAddress(addr, "100.64.0.1"));
        assertEquals(OutboundHttpException.Category.DNS_REBINDING, ex.category());
    }

    @Test
    @DisplayName("validateAddress blocks 0.0.0.0 (any-local)")
    void validateAddress_blocksAnyLocal() throws Exception {
        InetAddress addr = InetAddress.getByName("0.0.0.0");
        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class, () -> MediaSecurityValidator.validateAddress(addr, "0.0.0.0"));
        assertEquals(OutboundHttpException.Category.DNS_REBINDING, ex.category());
    }

    @Test
    @DisplayName("validateAddress blocks IPv6 loopback (::1), link-local (fe80::), and ULA (fc00::/7)")
    void validateAddress_blocksIpv6Internal() throws Exception {
        for (String ip : new String[] {"::1", "fe80::1", "fc00::1", "fd12:3456:789a::1"}) {
            InetAddress addr = InetAddress.getByName(ip);
            OutboundHttpException ex =
                    assertThrows(OutboundHttpException.class, () -> MediaSecurityValidator.validateAddress(addr, ip));
            assertEquals(OutboundHttpException.Category.DNS_REBINDING, ex.category());
        }
    }

    @Test
    @DisplayName("validateAddress blocks IPv4-mapped IPv6 loopback")
    void validateAddress_blocksIpv4MappedLoopback() throws Exception {
        InetAddress addr = InetAddress.getByName("::ffff:127.0.0.1");
        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class, () -> MediaSecurityValidator.validateAddress(addr, addr.getHostAddress()));
        assertEquals(OutboundHttpException.Category.DNS_REBINDING, ex.category());
    }

    @Test
    @DisplayName("validateAddress allows public IP addresses")
    void validateAddress_allowsPublicIps() throws Exception {
        InetAddress addr = InetAddress.getByName("93.184.216.34");
        assertDoesNotThrow(() -> MediaSecurityValidator.validateAddress(addr, "example.com"));
    }

    @Test
    @DisplayName("validateTarget rejects non-http/https schemes and missing hosts")
    void validateTarget_rejectsInvalidSchemesAndMissingHost() {
        assertThrows(NullPointerException.class, () -> MediaSecurityValidator.validateTarget(null));
        assertThrows(
                OutboundHttpException.class,
                () -> MediaSecurityValidator.validateTarget(URI.create("file:///etc/passwd")));
        assertThrows(
                OutboundHttpException.class,
                () -> MediaSecurityValidator.validateTarget(URI.create("javascript://alert(1)")));
        assertThrows(
                OutboundHttpException.class, () -> MediaSecurityValidator.validateTarget(URI.create("https:///path")));
    }

    @Test
    @DisplayName("validateRedirect enforces max hop count and revalidates target")
    void validateRedirect_enforcesHopCount() {
        URI next = URI.create("https://example.com/2");

        assertThrows(OutboundHttpException.class, () -> MediaSecurityValidator.validateRedirect(next, 5));
    }

    @Test
    @DisplayName("validateRedirect rejects credential-bearing redirect targets")
    void validateRedirect_rejectsCredentials() {
        assertThrows(
                OutboundHttpException.class,
                () -> MediaSecurityValidator.validateRedirect(
                        URI.create("https://127.0.0.1/image.png?redirect_token=secret"), 0));
        assertThrows(
                OutboundHttpException.class,
                () -> MediaSecurityValidator.validateRedirect(URI.create("https://user:pass@127.0.0.1/image.png"), 0));
        assertThrows(
                OutboundHttpException.class,
                () -> MediaSecurityValidator.validateRedirect(
                        URI.create("https://127.0.0.1/token/secret/image.png"), 0));
        assertThrows(
                OutboundHttpException.class,
                () -> MediaSecurityValidator.validateRedirect(
                        URI.create("https://127.0.0.1/bearer/secret/image.png"), 0));
        assertThrows(
                OutboundHttpException.class,
                () -> MediaSecurityValidator.validateRedirect(
                        URI.create("https://127.0.0.1/bot123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11/photo.jpg"), 0));
    }

    @Test
    @DisplayName("redirect reference validation does not resolve DNS before policy")
    void validateRedirectReference_defersDnsValidation() {
        assertDoesNotThrow(() ->
                MediaSecurityValidator.validateRedirectReference(URI.create("https://media.invalid/image.png"), 0));
        assertDoesNotThrow(() -> MediaSecurityValidator.validateRedirectReference(
                URI.create("https://media.invalid/image.png?view=true"), 0));
    }

    @Test
    @DisplayName("sameOrigin compares scheme host and effective port")
    void sameOrigin_comparesOrigin() {
        assertFalse(MediaSecurityValidator.sameOrigin(null, URI.create("https://example.com")));
        assertFalse(MediaSecurityValidator.sameOrigin(URI.create("https://example.com"), null));
        assertTrue(MediaSecurityValidator.sameOrigin(
                URI.create("https://Example.com/image.png"), URI.create("https://example.com/other.png")));
        assertTrue(MediaSecurityValidator.sameOrigin(
                URI.create("http://example.com:80/image.png"), URI.create("http://example.com/other.png")));
        assertFalse(MediaSecurityValidator.sameOrigin(
                URI.create("https://example.com/image.png"), URI.create("http://example.com/image.png")));
        assertFalse(MediaSecurityValidator.sameOrigin(
                URI.create("https://example.com/image.png"), URI.create("https://example.com:8443/image.png")));
    }

    @Test
    @DisplayName("validateMimeType validates allowed MIME types and matches attachment type")
    void validateMimeType_checks() {
        assertEquals(
                "image/png", MediaSecurityValidator.validateMimeType("image/png; charset=utf-8", AttachmentType.IMAGE));
        assertEquals(
                "application/pdf",
                MediaSecurityValidator.validateMimeType("application/pdf", AttachmentType.DOCUMENT_PDF));
        assertEquals("audio/mpeg", MediaSecurityValidator.validateMimeType("audio/mpeg", AttachmentType.AUDIO));

        // Rejects null or blank
        assertThrows(
                OutboundHttpException.class, () -> MediaSecurityValidator.validateMimeType(null, AttachmentType.IMAGE));
        assertThrows(
                OutboundHttpException.class, () -> MediaSecurityValidator.validateMimeType("", AttachmentType.IMAGE));

        // Rejects unsupported MIME
        assertThrows(
                OutboundHttpException.class,
                () -> MediaSecurityValidator.validateMimeType("application/x-sh", AttachmentType.DOCUMENT_PDF));

        // Rejects mismatched type
        assertThrows(
                OutboundHttpException.class,
                () -> MediaSecurityValidator.validateMimeType("image/png", AttachmentType.DOCUMENT_PDF));
        assertThrows(
                OutboundHttpException.class,
                () -> MediaSecurityValidator.validateMimeType("application/pdf", AttachmentType.IMAGE));
        assertThrows(
                OutboundHttpException.class,
                () -> MediaSecurityValidator.validateMimeType("image/png", AttachmentType.AUDIO));
    }

    @Test
    @DisplayName("Telegram octet-stream responses use the declared allowlisted image or audio MIME")
    void validateTelegramResponseMimeType_acceptsKnownImageAndAudio() {
        assertEquals(
                "image/jpeg",
                MediaSecurityValidator.validateTelegramResponseMimeType(
                        "application/octet-stream; charset=binary", "image/jpeg", AttachmentType.IMAGE));
        assertEquals(
                "audio/mpeg",
                MediaSecurityValidator.validateTelegramResponseMimeType(
                        "application/octet-stream", "audio/mpeg", AttachmentType.AUDIO));
        assertEquals(
                "audio/ogg",
                MediaSecurityValidator.validateTelegramResponseMimeType(
                        "application/octet-stream", "audio/ogg", AttachmentType.AUDIO));
    }

    @Test
    @DisplayName("Telegram octet-stream fallback remains fail-closed for unknown or mismatched declarations")
    void validateTelegramResponseMimeType_rejectsUnknownOrMismatchedDeclaration() {
        assertThrows(
                OutboundHttpException.class,
                () -> MediaSecurityValidator.validateTelegramResponseMimeType(
                        "application/octet-stream", "application/octet-stream", AttachmentType.IMAGE));
        assertThrows(
                OutboundHttpException.class,
                () -> MediaSecurityValidator.validateTelegramResponseMimeType(
                        "application/octet-stream", "image/jpeg", AttachmentType.AUDIO));
        assertThrows(
                OutboundHttpException.class,
                () -> MediaSecurityValidator.validateTelegramResponseMimeType(
                        "application/octet-stream", "image/jpeg", AttachmentType.OTHER));
        assertThrows(
                OutboundHttpException.class,
                () -> MediaSecurityValidator.validateMimeType("application/octet-stream", AttachmentType.IMAGE));
    }

    @Test
    @DisplayName("validateMagicBytes recognizes valid headers for PNG, JPEG, GIF, PDF, WebP, MPEG, WAV, OGG")
    void validateMagicBytes_validHeaders() {
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        assertDoesNotThrow(() -> MediaSecurityValidator.validateMagicBytes(png, "image/png"));

        byte[] jpeg = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        assertDoesNotThrow(() -> MediaSecurityValidator.validateMagicBytes(jpeg, "image/jpeg"));

        byte[] gif = new byte[] {'G', 'I', 'F', '8', '9', 'a'};
        assertDoesNotThrow(() -> MediaSecurityValidator.validateMagicBytes(gif, "image/gif"));

        byte[] webp = new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
        assertDoesNotThrow(() -> MediaSecurityValidator.validateMagicBytes(webp, "image/webp"));

        byte[] pdf = new byte[] {'%', 'P', 'D', 'F', '-', '1', '.', '7'};
        assertDoesNotThrow(() -> MediaSecurityValidator.validateMagicBytes(pdf, "application/pdf"));

        byte[] mpegId3 = new byte[] {'I', 'D', '3', 0x04};
        assertDoesNotThrow(() -> MediaSecurityValidator.validateMagicBytes(mpegId3, "audio/mpeg"));

        byte[] mpegFrame = new byte[] {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, (byte) 0x44};
        assertDoesNotThrow(() -> MediaSecurityValidator.validateMagicBytes(mpegFrame, "audio/mpeg"));

        byte[] wav = new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'};
        assertDoesNotThrow(() -> MediaSecurityValidator.validateMagicBytes(wav, "audio/wav"));

        byte[] ogg = new byte[] {'O', 'g', 'g', 'S'};
        assertDoesNotThrow(() -> MediaSecurityValidator.validateMagicBytes(ogg, "audio/ogg"));
    }

    @Test
    @DisplayName("validateMagicBytes rejects scripts and deceptive HTML/XML headers")
    void validateMagicBytes_rejectsScriptsAndHtml() {
        byte[] html = "<!DOCTYPE html><html>".getBytes(StandardCharsets.UTF_8);
        assertThrows(OutboundHttpException.class, () -> MediaSecurityValidator.validateMagicBytes(html, "image/png"));

        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\">".getBytes(StandardCharsets.UTF_8);
        assertThrows(OutboundHttpException.class, () -> MediaSecurityValidator.validateMagicBytes(svg, "image/png"));

        byte[] script = "#!/bin/bash\nrm -rf /".getBytes(StandardCharsets.UTF_8);
        assertThrows(
                OutboundHttpException.class, () -> MediaSecurityValidator.validateMagicBytes(script, "image/jpeg"));

        byte[] corruptedPng = new byte[] {0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07};
        assertThrows(
                OutboundHttpException.class,
                () -> MediaSecurityValidator.validateMagicBytes(corruptedPng, "image/png"));

        byte[] corruptedJpeg = new byte[] {0x00, 0x01, 0x02, 0x03};
        assertThrows(
                OutboundHttpException.class,
                () -> MediaSecurityValidator.validateMagicBytes(corruptedJpeg, "image/jpeg"));

        byte[] corruptedGif = new byte[] {'B', 'A', 'D', '0'};
        assertThrows(
                OutboundHttpException.class,
                () -> MediaSecurityValidator.validateMagicBytes(corruptedGif, "image/gif"));

        byte[] corruptedWebp = new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'B', 'A', 'D', '0'};
        assertThrows(
                OutboundHttpException.class,
                () -> MediaSecurityValidator.validateMagicBytes(corruptedWebp, "image/webp"));

        byte[] corruptedPdf = new byte[] {'%', 'D', 'O', 'C'};
        assertThrows(
                OutboundHttpException.class,
                () -> MediaSecurityValidator.validateMagicBytes(corruptedPdf, "application/pdf"));

        byte[] corruptedMpeg = new byte[] {'M', 'P', '3', '0'};
        assertThrows(
                OutboundHttpException.class,
                () -> MediaSecurityValidator.validateMagicBytes(corruptedMpeg, "audio/mpeg"));

        byte[] corruptedWav = new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'B', 'A', 'D', '0'};
        assertThrows(
                OutboundHttpException.class,
                () -> MediaSecurityValidator.validateMagicBytes(corruptedWav, "audio/wav"));

        byte[] corruptedOgg = new byte[] {'B', 'A', 'D', '0'};
        assertThrows(
                OutboundHttpException.class,
                () -> MediaSecurityValidator.validateMagicBytes(corruptedOgg, "audio/ogg"));

        assertThrows(
                OutboundHttpException.class,
                () -> MediaSecurityValidator.validateMagicBytes(new byte[] {(byte) 0x89, 'P', 'N'}, "image/png"));

        assertThrows(
                OutboundHttpException.class,
                () -> MediaSecurityValidator.validateMagicBytes(new byte[] {1, 2, 3, 4}, null));
    }
}
