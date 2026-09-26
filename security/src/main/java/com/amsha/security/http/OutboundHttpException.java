package com.amsha.security.http;

/**
 * Backwards-compatibility wrapper extending {@link com.amsha.core.security.http.OutboundHttpException}.
 */
public class OutboundHttpException extends com.amsha.core.security.http.OutboundHttpException {

    public OutboundHttpException(Category category, String message) {
        super(category, message);
    }

    public OutboundHttpException(Category category, String message, Throwable cause) {
        super(category, message, cause);
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
