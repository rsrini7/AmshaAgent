package com.amsha.security.http;

/**
 * Thrown when the governed HTTP transport refuses, fails to validate, or cannot safely complete an
 * outbound request. Carries a bounded category for safe error reporting and audit (never a provider
 * response body, header, or resolved IP).
 */
public class OutboundHttpException extends RuntimeException {

    public enum Category {
        /** Policy service denied the request (SSRF, TLS, proxy, host-allow-list). */
        POLICY_DENIED,
        /** The request requires human approval before the transport may execute it. */
        HITL_REQUIRED,
        /** DNS revalidation immediately before connect resolved to a prohibited address. */
        DNS_REBINDING,
        /** A redirect hop targeted a prohibited or policy-disallowed host. */
        REDIRECT_DENIED,
        /** Too many redirect hops followed. */
        REDIRECT_LIMIT,
        /** Connect or read exceeded the bounded timeout. */
        TIMEOUT,
        /** TLS/custom-CA trust establishment failed. */
        TLS_TRUST,
        /** The target host could not be resolved. */
        DNS_FAILURE,
        /** Media payload exceeded the declared size limits or turn budget. */
        OVERSIZED_PAYLOAD,
        /** MIME type is unsupported or does not match the attachment modality. */
        UNSUPPORTED_MIME,
        /** Magic bytes do not match declared MIME type. */
        MAGIC_MISMATCH,
        /** Lower-level I/O failure (connection refused, reset, proxy error). */
        IO
    }

    private final Category category;

    public OutboundHttpException(Category category, String message) {
        super(message);
        this.category = category;
    }

    public OutboundHttpException(Category category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public Category category() {
        return category;
    }

    public static OutboundHttpException policy(String message) {
        return new OutboundHttpException(Category.POLICY_DENIED, message);
    }

    public static OutboundHttpException hitl(String message) {
        return new OutboundHttpException(Category.HITL_REQUIRED, message);
    }

    public static OutboundHttpException dnsRebinding(String message) {
        return new OutboundHttpException(Category.DNS_REBINDING, message);
    }

    public static OutboundHttpException redirectDenied(String message) {
        return new OutboundHttpException(Category.REDIRECT_DENIED, message);
    }

    public static OutboundHttpException timeout(String message) {
        return new OutboundHttpException(Category.TIMEOUT, message);
    }

    public static OutboundHttpException tls(String message) {
        return new OutboundHttpException(Category.TLS_TRUST, message);
    }

    public static OutboundHttpException dns(String message) {
        return new OutboundHttpException(Category.DNS_FAILURE, message);
    }

    public static OutboundHttpException oversized(String message) {
        return new OutboundHttpException(Category.OVERSIZED_PAYLOAD, message);
    }

    public static OutboundHttpException unsupportedMime(String message) {
        return new OutboundHttpException(Category.UNSUPPORTED_MIME, message);
    }

    public static OutboundHttpException magicMismatch(String message) {
        return new OutboundHttpException(Category.MAGIC_MISMATCH, message);
    }

    public static OutboundHttpException io(String message) {
        return new OutboundHttpException(Category.IO, message);
    }

    public static OutboundHttpException io(String message, Throwable cause) {
        return new OutboundHttpException(Category.IO, message, cause);
    }
}
