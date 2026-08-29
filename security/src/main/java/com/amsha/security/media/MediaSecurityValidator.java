package com.amsha.security.media;

import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.amsha.channel.api.AttachmentType;
import com.amsha.core.scope.ContextAwareExecutorService;
import com.amsha.security.api.MediaBudgetLimits;
import com.amsha.security.http.OutboundHttpException;

/**
 * Validates external media download targets against SSRF, loopback, private IP ranges,
 * cloud metadata endpoints, DNS rebinding, redirect boundaries, permitted MIME types,
 * and binary magic-byte signatures.
 */
public final class MediaSecurityValidator {

    private static final int MAX_MAGIC_HEADER_BYTES = 32;
    private static final Set<String> FORBIDDEN_QUERY_PARAMETER_NAMES = Set.of(
            "token",
            "access_token",
            "refresh_token",
            "id_token",
            "auth_token",
            "redirect_token",
            "key",
            "secret",
            "auth",
            "password",
            "bearer",
            "apikey",
            "api_key",
            "authorization");

    private MediaSecurityValidator() {}

    /**
     * Performs JIT DNS resolution and validation immediately before initiating a connection.
     *
     * @param uri the target URI to validate
     * @throws OutboundHttpException if the target violates safety constraints
     */
    public static void validateTarget(URI uri) {
        validateTarget(uri, Duration.ofSeconds(MediaBudgetLimits.DEFAULT_CONNECT_TIMEOUT_SECONDS));
    }

    /** Performs target validation with a bounded DNS resolution window. */
    public static void validateTarget(URI uri, Duration dnsTimeout) {
        Objects.requireNonNull(uri, "uri must not be null");
        String host = validateHttpUri(uri);
        validateHostDns(host, dnsTimeout);
    }

    /**
     * Resolves the host via DNS and validates all returned IP addresses against prohibited ranges.
     */
    public static void validateHostDns(String host) {
        validateHostDns(host, Duration.ofSeconds(MediaBudgetLimits.DEFAULT_CONNECT_TIMEOUT_SECONDS));
    }

    /** Resolves and validates a host without allowing DNS resolution to run indefinitely. */
    public static void validateHostDns(String host, Duration dnsTimeout) {
        resolveAndValidateHost(host, dnsTimeout);
    }

    /** Resolves, validates, and returns the JIT address set for the governed transport. */
    public static InetAddress[] resolveAndValidateHost(String host, Duration dnsTimeout) {
        String normalized = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;

        if (dnsTimeout == null || dnsTimeout.isZero() || dnsTimeout.isNegative()) {
            throw new IllegalArgumentException("dnsTimeout must be positive");
        }
        try (ContextAwareExecutorService resolverExecutor =
                new ContextAwareExecutorService(Executors.newVirtualThreadPerTaskExecutor())) {
            Future<InetAddress[]> resolution = resolverExecutor.submit(() -> InetAddress.getAllByName(normalized));
            InetAddress[] addresses;
            try {
                long timeoutMillis = Math.max(1L, dnsTimeout.toMillis());
                addresses = resolution.get(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException _) {
                resolution.cancel(true);
                throw OutboundHttpException.timeout("DNS resolution timed out for media target");
            } catch (InterruptedException e) {
                resolution.cancel(true);
                Thread.currentThread().interrupt();
                throw OutboundHttpException.dns("DNS resolution interrupted for media target");
            } catch (ExecutionException e) {
                resolution.cancel(true);
                throw OutboundHttpException.dns("DNS resolution failed for media target");
            }
            if (addresses.length == 0) {
                throw OutboundHttpException.dns("DNS resolution returned no addresses for " + host);
            }
            for (InetAddress addr : addresses) {
                validateAddress(addr, host);
            }
            return addresses;
        } catch (OutboundHttpException e) {
            throw e;
        } catch (Exception _) {
            throw OutboundHttpException.dns("DNS resolution failed for " + host);
        }
    }

    public static void validateAddress(InetAddress addr, String host) {
        if (addr.isLoopbackAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()
                || addr.isMulticastAddress()
                || addr.isAnyLocalAddress()) {
            throw OutboundHttpException.dnsRebinding(
                    "Resolved address for " + host + " is in a prohibited private/loopback/link-local range");
        }

        byte[] bytes = addr.getAddress();
        if (isIpv4MappedAddress(bytes)) {
            throw OutboundHttpException.dnsRebinding("Resolved address for " + host + " is an IPv4-mapped address");
        }
        // Explicit check for cloud metadata (169.254.169.254)
        if (bytes.length == 4
                && (bytes[0] & 0xff) == 169
                && (bytes[1] & 0xff) == 254
                && (bytes[2] & 0xff) == 169
                && (bytes[3] & 0xff) == 254) {
            throw OutboundHttpException.dnsRebinding(
                    "Resolved address for " + host + " is the cloud metadata endpoint (169.254.169.254)");
        }

        // CGNAT (100.64.0.0/10)
        if (bytes.length == 4 && (bytes[0] & 0xff) == 100 && ((bytes[1] & 0xff) >= 64 && (bytes[1] & 0xff) <= 127)) {
            throw OutboundHttpException.dnsRebinding(
                    "Resolved address for " + host + " is in CGNAT shared address space (100.64.0.0/10)");
        }

        // IPv6 Unique Local Address (ULA) fc00::/7
        if (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc) {
            throw OutboundHttpException.dnsRebinding(
                    "Resolved address for " + host + " is an IPv6 Unique Local Address (fc00::/7)");
        }
    }

    /**
     * Validates redirect hop count and re-resolves the target IP of the redirect hop.
     */
    public static void validateRedirect(URI nextUri, int hopCount) {
        validateRedirectReference(nextUri, hopCount);
        validateHostDns(nextUri.getHost());
    }

    /**
     * Validates a redirect reference without resolving DNS. Callers must evaluate outbound
     * policy/HITL before invoking {@link #validateTarget(URI)} for the connection.
     */
    static void validateRedirectReference(URI nextUri, int hopCount) {
        if (hopCount >= MediaBudgetLimits.MAX_REDIRECT_HOPS) {
            throw OutboundHttpException.redirectDenied(
                    "Too many redirect hops (exceeded limit of " + MediaBudgetLimits.MAX_REDIRECT_HOPS + ")");
        }
        validateRedirectUriCredentials(nextUri);
        validateHttpUri(nextUri);
    }

    private static String validateHttpUri(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw OutboundHttpException.policy("Media download requires http or https scheme: " + scheme);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw OutboundHttpException.policy("Media download URI missing host");
        }
        return host;
    }

    /**
     * Returns whether two HTTP(S) URIs share the same scheme, host, and effective port.
     * A previously approved media origin must not silently approve a cross-origin redirect.
     */
    public static boolean sameOrigin(URI first, URI second) {
        if (first == null || second == null) {
            return false;
        }
        String firstHost = first.getHost();
        String secondHost = second.getHost();
        return firstHost != null
                && secondHost != null
                && first.getScheme() != null
                && first.getScheme().equalsIgnoreCase(second.getScheme())
                && firstHost.equalsIgnoreCase(secondHost)
                && effectivePort(first) == effectivePort(second);
    }

    /**
     * Validates content-type against the permitted MIME allowlist and attachment type.
     */
    public static String validateMimeType(String rawContentType, AttachmentType expectedType) {
        if (rawContentType == null || rawContentType.isBlank()) {
            throw OutboundHttpException.policy("Response missing Content-Type header");
        }
        String normalized = rawContentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (!MediaBudgetLimits.ALLOWED_MIME_TYPES.contains(normalized)) {
            throw OutboundHttpException.policy("Unsupported media MIME type: " + normalized + " (allowed: "
                    + MediaBudgetLimits.ALLOWED_MIME_TYPES + ")");
        }

        if (expectedType == AttachmentType.IMAGE && !normalized.startsWith("image/")) {
            throw OutboundHttpException.policy(
                    "MIME type " + normalized + " does not match expected attachment type " + expectedType);
        }
        if (expectedType == AttachmentType.AUDIO && !normalized.startsWith("audio/")) {
            throw OutboundHttpException.policy(
                    "MIME type " + normalized + " does not match expected attachment type " + expectedType);
        }
        if (expectedType == AttachmentType.DOCUMENT_PDF && !"application/pdf".equals(normalized)) {
            throw OutboundHttpException.policy(
                    "MIME type " + normalized + " does not match expected attachment type " + expectedType);
        }
        return normalized;
    }

    /**
     * Inspects initial payload header bytes to confirm magic bytes match the expected MIME type
     * and reject deceptive executable or script payloads.
     */
    public static void validateMagicBytes(byte[] header, String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            throw OutboundHttpException.policy("Media payload MIME type is missing");
        }
        int requiredHeaderLength = requiredMagicHeaderLength(mimeType);
        if (header == null || header.length < requiredHeaderLength) {
            throw OutboundHttpException.policy("Media payload is empty or too short for valid " + mimeType + " header");
        }

        rejectExecutableHeader(header);
        switch (mimeType) {
            case "image/png" -> validatePngHeader(header);
            case "image/jpeg" -> validateJpegHeader(header);
            case "image/gif" -> validateGifHeader(header);
            case "image/webp" -> validateWebpHeader(header);
            case "application/pdf" -> validatePdfHeader(header);
            case "audio/mpeg" -> validateMpegHeader(header);
            case "audio/wav" -> validateWavHeader(header);
            case "audio/ogg" -> validateOggHeader(header);
            default -> {
                // The MIME allowlist is enforced by validateMimeType.
            }
        }
    }

    private static void rejectExecutableHeader(byte[] header) {
        String prefix = new String(
                        header, 0, Math.min(header.length, MAX_MAGIC_HEADER_BYTES), StandardCharsets.US_ASCII)
                .toLowerCase(Locale.ROOT);
        if (prefix.startsWith("<!doctype")
                || prefix.startsWith("<html")
                || prefix.startsWith("<svg")
                || prefix.startsWith("<?xml")
                || prefix.startsWith("#!/")
                || prefix.startsWith("mz")) {
            throw OutboundHttpException.policy("Payload contains prohibited script or executable header signatures");
        }
    }

    private static void validatePngHeader(byte[] header) {
        if (!matchesBytes(header, new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})) {
            throw OutboundHttpException.policy("Image data does not match valid PNG magic header");
        }
    }

    private static void validateJpegHeader(byte[] header) {
        if ((header[0] & 0xFF) != 0xFF || (header[1] & 0xFF) != 0xD8 || (header[2] & 0xFF) != 0xFF) {
            throw OutboundHttpException.policy("Image data does not match valid JPEG magic header");
        }
    }

    private static void validateGifHeader(byte[] header) {
        if (!matchesAscii(header, 0, "GIF8")) {
            throw OutboundHttpException.policy("Image data does not match valid GIF magic header");
        }
    }

    private static void validateWebpHeader(byte[] header) {
        if (!matchesAscii(header, 0, "RIFF") || !matchesAscii(header, 8, "WEBP")) {
            throw OutboundHttpException.policy("Image data does not match valid WebP magic header");
        }
    }

    private static void validatePdfHeader(byte[] header) {
        if (!matchesAscii(header, 0, "%PDF")) {
            throw OutboundHttpException.policy("Document data does not match valid PDF magic header");
        }
    }

    private static void validateMpegHeader(byte[] header) {
        boolean id3 = matchesAscii(header, 0, "ID3");
        boolean frame = (header[0] & 0xFF) == 0xFF && (header[1] & 0xE0) == 0xE0;
        if (!id3 && !frame) {
            throw OutboundHttpException.policy("Audio data does not match valid MPEG magic header");
        }
    }

    private static void validateWavHeader(byte[] header) {
        if (!matchesAscii(header, 0, "RIFF") || !matchesAscii(header, 8, "WAVE")) {
            throw OutboundHttpException.policy("Audio data does not match valid WAV magic header");
        }
    }

    private static void validateOggHeader(byte[] header) {
        if (!matchesAscii(header, 0, "OggS")) {
            throw OutboundHttpException.policy("Audio data does not match valid OGG magic header");
        }
    }

    private static boolean matchesBytes(byte[] actual, byte[] expected) {
        if (actual.length < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (actual[i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static int requiredMagicHeaderLength(String mimeType) {
        return switch (mimeType) {
            case "image/png" -> 8;
            case "image/webp", "audio/wav" -> 12;
            default -> 4;
        };
    }

    private static boolean matchesAscii(byte[] bytes, int offset, String expected) {
        if (offset < 0 || bytes.length < offset + expected.length()) {
            return false;
        }
        for (int i = 0; i < expected.length(); i++) {
            if (bytes[offset + i] != (byte) expected.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIpv4MappedAddress(byte[] bytes) {
        if (bytes.length != 16) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF;
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static void validateRedirectUriCredentials(URI uri) {
        if (uri == null) {
            throw OutboundHttpException.redirectDenied("Redirect target is missing");
        }
        if (uri.getUserInfo() != null) {
            throw OutboundHttpException.redirectDenied("Redirect target must not contain embedded credentials");
        }

        String rawPath = uri.getRawPath();
        if (rawPath != null) {
            String lowerPath = rawPath.toLowerCase(Locale.ROOT);
            if (lowerPath.contains("/token/") || lowerPath.contains("/bearer/") || lowerPath.contains("/secret/")) {
                throw OutboundHttpException.redirectDenied(
                        "Redirect target path must not contain embedded credential path segments");
            }
            if (lowerPath.contains("/bot") && lowerPath.matches("(?i).*/bot[0-9]+:[a-z0-9_-]+.*")) {
                throw OutboundHttpException.redirectDenied(
                        "Redirect target must not contain a Telegram bot token path");
            }
        }

        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return;
        }
        for (String pair : rawQuery.split("&", -1)) {
            String rawName = pair;
            int equalsIndex = pair.indexOf('=');
            if (equalsIndex >= 0) {
                rawName = pair.substring(0, equalsIndex);
            }
            if (rawName.isEmpty()) {
                continue;
            }
            final String parameterName;
            try {
                parameterName =
                        URLDecoder.decode(rawName, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException _) {
                throw OutboundHttpException.redirectDenied("Redirect target has malformed query encoding");
            }
            if (FORBIDDEN_QUERY_PARAMETER_NAMES.contains(parameterName)) {
                throw OutboundHttpException.redirectDenied(
                        "Redirect target must not contain sensitive credential query parameters");
            }
        }
    }
}
