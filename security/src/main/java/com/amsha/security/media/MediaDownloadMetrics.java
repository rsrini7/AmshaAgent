package com.amsha.security.media;

import java.util.concurrent.TimeUnit;

import com.amsha.channel.api.AttachmentType;
import com.amsha.channel.api.MediaAttachment;
import com.amsha.channel.api.MediaTagValues;
import com.amsha.security.http.OutboundHttpException;

import io.micrometer.core.instrument.MeterRegistry;

final class MediaDownloadMetrics {

    static final String DOWNLOAD_TOTAL = "amsha.media.download.total";
    static final String DOWNLOAD_DURATION = "amsha.media.download.duration";
    static final String DOWNLOAD_BYTES = "amsha.media.download.bytes";

    private MediaDownloadMetrics() {}

    static void recordDownload(
            MeterRegistry registry, MediaAttachment attachment, String status, long durationNanos, long bytes) {
        if (registry == null) {
            return;
        }
        try {
            String source = resolveSource(attachment);
            String type = resolveType(attachment != null ? attachment.type() : null);
            String safeStatus = resolveStatus(status);

            registry.counter(DOWNLOAD_TOTAL, "source", source, "type", type, "status", safeStatus)
                    .increment();
            registry.timer(DOWNLOAD_DURATION, "source", source, "type", type, "status", safeStatus)
                    .record(durationNanos, TimeUnit.NANOSECONDS);

            if ("success".equals(safeStatus) && bytes > 0) {
                registry.counter(DOWNLOAD_BYTES, "source", source, "type", type).increment(bytes);
            }
        } catch (Exception _) {
            // Metrics must never fail media operations
        }
    }

    static String resolveSource(MediaAttachment attachment) {
        return MediaTagValues.resolveSource(attachment);
    }

    static String resolveType(AttachmentType type) {
        return MediaTagValues.resolveType(type);
    }

    static String resolveStatus(String status) {
        return MediaTagValues.resolveStatus(status);
    }

    static String mapExceptionToStatus(Exception e) {
        if (e == null) {
            return "success";
        }
        if (e instanceof OutboundHttpException ohe) {
            return switch (ohe.category()) {
                case POLICY_DENIED -> "policy_denied";
                case HITL_REQUIRED -> "hitl_required";
                case TIMEOUT -> "timeout";
                case REDIRECT_DENIED, REDIRECT_LIMIT -> "redirect_denied";
                case DNS_REBINDING, TLS_TRUST -> "security_violation";
                case OVERSIZED_PAYLOAD -> "oversized";
                case UNSUPPORTED_MIME -> "unsupported_mime";
                case MAGIC_MISMATCH -> "magic_mismatch";
                case DNS_FAILURE, IO -> "error";
            };
        }
        if (e instanceof SecurityException) {
            return "security_violation";
        }
        if (e instanceof IllegalStateException) {
            return "state_error";
        }
        return "error";
    }
}
