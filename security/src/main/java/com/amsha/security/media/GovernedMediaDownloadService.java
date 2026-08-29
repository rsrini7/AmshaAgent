package com.amsha.security.media;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.amsha.channel.api.MediaAttachment;
import com.amsha.common.util.LogUtil;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.scope.ContextAwareExecutorService;
import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.security.api.DownloadedMedia;
import com.amsha.security.api.MediaBudgetLimits;
import com.amsha.security.api.MediaDownloadPort;
import com.amsha.security.api.OutboundPolicyDecision;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.http.OutboundHttpException;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;
import io.vertx.core.Context;

/**
 * Governed implementation of {@link MediaDownloadPort} that enforces SSRF checks,
 * JIT DNS revalidation, redirect-hop revalidation, streaming byte budgets,
 * turn-level payload limits, MIME allowlists, magic-byte checks, HITL gating,
 * read timeout deadlines, and deterministic temp file lifecycles.
 */
@Unremovable
@ApplicationScoped
public class GovernedMediaDownloadService implements MediaDownloadPort {

    private static final Logger log = Logger.getLogger(GovernedMediaDownloadService.class);
    private static final int IN_MEMORY_THRESHOLD_BYTES = 1024 * 1024; // 1 MB
    private static final int STREAM_BUFFER_SIZE = 8192;

    @Inject
    OutboundHttpPolicyService policyService;

    @Inject
    AuditService auditService;

    @ConfigProperty(name = "amsha.data-dir", defaultValue = "./data")
    String dataDir;

    @ConfigProperty(name = "amsha.media.max-image-size-mb", defaultValue = "10")
    int maxImageSizeMb;

    @ConfigProperty(name = "amsha.media.max-document-size-mb", defaultValue = "25")
    int maxDocSizeMb;

    @ConfigProperty(name = "amsha.media.max-total-payload-mb", defaultValue = "50")
    int maxTotalPayloadMb;

    @ConfigProperty(name = "amsha.media.connect-timeout-seconds", defaultValue = "10")
    int connectTimeoutSeconds;

    @ConfigProperty(name = "amsha.media.read-timeout-seconds", defaultValue = "30")
    int readTimeoutSeconds;

    @Override
    public DownloadedMedia download(MediaAttachment attachment, String actorUserId, boolean preApproved) {
        return downloadWithMaximumBytes(attachment, actorUserId, preApproved, configuredMaxTotalPayloadBytes());
    }

    @Override
    public long maxTotalPayloadBytes() {
        return configuredMaxTotalPayloadBytes();
    }

    private DownloadedMedia downloadWithMaximumBytes(
            MediaAttachment attachment, String actorUserId, boolean preApproved, long maximumBytes) {
        Objects.requireNonNull(attachment, "attachment must not be null");
        if (maximumBytes <= 0) {
            throw OutboundHttpException.policy("Media download has no remaining payload budget");
        }

        // Non-blocking event loop safety guard
        if (Context.isOnEventLoopThread()) {
            throw new IllegalStateException("GovernedMediaDownloadService must not run on a Vert.x event loop thread");
        }

        DownloadedMedia result = null;
        try {
            if (attachment.inlineData() != null) {
                result = processInlineMedia(attachment, maximumBytes);
            } else {
                URI sourceUri = attachment.sourceUri();
                if (sourceUri == null) {
                    throw new IllegalArgumentException("MediaAttachment has neither inlineData nor sourceUri");
                }

                String scheme = sourceUri.getScheme();
                if ("telegram".equalsIgnoreCase(scheme)) {
                    throw OutboundHttpException.policy(
                            "Telegram media references must be resolved by the channel adapter");
                }
                if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                    throw OutboundHttpException.policy("Unsupported media download scheme: " + scheme);
                }

                // Evaluate Outbound Policy & HITL before any DNS or transport work.
                OutboundPolicyDecision decision = policyService.evaluate(
                        sourceUri.toString(), OutboundPurpose.MEDIA_DOWNLOAD, null, actorUserId, preApproved);
                if (decision.isDenied()) {
                    log.warnf(
                            "Media download denied: %s (reason: %s)",
                            sourceUri.getHost(), decision.reason().orElse("policy"));
                    throw OutboundHttpException.policy(
                            "Media download denied: " + decision.reason().orElse("policy"));
                }
                if (decision.requiresHitl()) {
                    log.infof("Media download for %s requires HITL approval", sourceUri.getHost());
                    throw OutboundHttpException.hitl(decision.reason().orElse("approval required"));
                }

                // Stream HTTP download with redirect validation and streaming size bounds.
                result = downloadHttpWithRedirects(
                        sourceUri, attachment, actorUserId, preApproved, sourceUri, maximumBytes, 0);
            }

            auditService.recordMandatory(
                    "media-download",
                    actorUserId != null ? actorUserId : "system",
                    "system",
                    SecurityEventType.MEDIA_DOWNLOADED,
                    "action=media_downloaded type=" + attachment.type() + " size=" + result.sizeBytes());

            return result;
        } catch (Exception e) {
            // Clean up temp file immediately if download or mandatory audit fails
            if (result != null) {
                result.close();
            }
            throw e;
        }
    }

    @Override
    public List<DownloadedMedia> downloadAll(
            List<MediaAttachment> attachments, String actorUserId, boolean preApproved) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }

        long cumulativeBytes = 0;
        long maxTotalBytes = configuredMaxTotalPayloadBytes();
        List<DownloadedMedia> downloadedList = new ArrayList<>(attachments.size());

        try {
            for (MediaAttachment attachment : attachments) {
                long remainingBytes = maxTotalBytes - cumulativeBytes;
                if (remainingBytes <= 0) {
                    throw OutboundHttpException.policy(
                            "Cumulative turn media payload reached limit of " + maxTotalBytes + " bytes");
                }
                DownloadedMedia downloaded =
                        downloadWithMaximumBytes(attachment, actorUserId, preApproved, remainingBytes);
                cumulativeBytes += downloaded.sizeBytes();
                if (cumulativeBytes > maxTotalBytes) {
                    downloaded.close();
                    throw OutboundHttpException.policy("Cumulative turn media payload exceeded limit of "
                            + maxTotalBytes + " bytes (actual: " + cumulativeBytes + ")");
                }
                downloadedList.add(downloaded);
            }
            return List.copyOf(downloadedList);
        } catch (Exception e) {
            // Clean up all successfully downloaded media so far in the turn
            for (DownloadedMedia m : downloadedList) {
                m.close();
            }
            throw e;
        }
    }

    private DownloadedMedia processInlineMedia(MediaAttachment attachment, long maximumBytes) {
        byte[] data = attachment.inlineData();
        long limitBytes =
                MediaBudgetLimits.resolveLimitForType(attachment.type(), maxImageSizeBytes(), maxDocSizeBytes());
        limitBytes = Math.min(limitBytes, maximumBytes);
        if (data.length > limitBytes) {
            throw OutboundHttpException.policy("Inline media exceeds maximum size limit of " + limitBytes
                    + " bytes (actual: " + data.length + ")");
        }
        String validMime = MediaSecurityValidator.validateMimeType(attachment.mimeType(), attachment.type());
        MediaSecurityValidator.validateMagicBytes(data, validMime);
        return DownloadedMedia.ofInline(
                attachment.attachmentId(), attachment.type(), validMime, attachment.fileName(), data);
    }

    private DownloadedMedia downloadHttpWithRedirects(
            URI uri,
            MediaAttachment attachment,
            String actorUserId,
            boolean preApproved,
            URI approvalOrigin,
            long maximumBytes,
            int depth) {
        if (depth >= MediaBudgetLimits.MAX_REDIRECT_HOPS) {
            throw OutboundHttpException.redirectDenied(
                    "Too many redirect hops during media download (> " + MediaBudgetLimits.MAX_REDIRECT_HOPS + ")");
        }

        // JIT DNS revalidation immediately before connecting
        MediaSecurityValidator.validateTarget(uri);

        // Explicitly disable JDK auto-redirects so each redirect hop passes through JIT DNS and policy revalidation
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(effectiveConnectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(effectiveReadTimeoutSeconds()))
                .GET()
                .build();

        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            log.warnf("Media download timed out for %s: %s", uri.getHost(), LogUtil.rootMessage(e));
            throw OutboundHttpException.timeout("Media download timed out: " + uri.getHost());
        } catch (IOException e) {
            log.warnf("Media download I/O failure for %s: %s", uri.getHost(), LogUtil.rootMessage(e));
            throw OutboundHttpException.io("Media download I/O failure for " + uri.getHost(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warnf("Media download interrupted for %s: %s", uri.getHost(), LogUtil.rootMessage(e));
            throw OutboundHttpException.io("Media download interrupted for " + uri.getHost(), e);
        }

        int status = response.statusCode();
        if (status >= 300 && status < 400) {
            // Drain/close redirect response body to prevent connection leaks
            try (InputStream body = response.body()) {
                // drain
            } catch (IOException _) {
            }

            String location = response.headers().firstValue("location").orElse(null);
            if (location == null || location.isBlank()) {
                throw OutboundHttpException.redirectDenied("HTTP " + status + " redirect with no Location header");
            }
            URI nextUri = uri.resolve(location);
            // Validate syntax and credentials before policy; defer DNS until policy/HITL passes.
            MediaSecurityValidator.validateRedirectReference(nextUri, depth + 1);

            // Re-evaluate policy for the redirect hop target
            boolean sameOrigin = MediaSecurityValidator.sameOrigin(approvalOrigin, nextUri);
            boolean redirectPreApproved = preApproved && sameOrigin;
            OutboundPolicyDecision redirDecision = policyService.evaluate(
                    nextUri.toString(), OutboundPurpose.MEDIA_DOWNLOAD, null, actorUserId, redirectPreApproved);
            if (redirDecision.isDenied()) {
                throw OutboundHttpException.redirectDenied("Redirect target denied by outbound policy: "
                        + redirDecision.reason().orElse("policy"));
            }
            if (redirDecision.requiresHitl()) {
                throw OutboundHttpException.hitl("Redirect to " + nextUri.getHost() + " requires HITL approval: "
                        + redirDecision.reason().orElse("approval required"));
            }
            if (preApproved && !sameOrigin) {
                throw OutboundHttpException.hitl(
                        "Redirect crosses media approval origin and requires fresh HITL approval");
            }

            return downloadHttpWithRedirects(
                    nextUri, attachment, actorUserId, preApproved, approvalOrigin, maximumBytes, depth + 1);
        }

        if (status != 200) {
            try (InputStream body = response.body()) {
                // drain
            } catch (IOException _) {
            }
            throw OutboundHttpException.io("Media download failed with HTTP status " + status);
        }

        InputStream responseBody = response.body();
        try {
            String rawContentType =
                    response.headers().firstValue("content-type").orElse(null);
            String validMime = MediaSecurityValidator.validateMimeType(rawContentType, attachment.type());

            long limitBytes =
                    MediaBudgetLimits.resolveLimitForType(attachment.type(), maxImageSizeBytes(), maxDocSizeBytes());
            limitBytes = Math.min(limitBytes, maximumBytes);

            return streamResponseBody(responseBody, attachment, validMime, limitBytes);
        } catch (RuntimeException e) {
            closeQuietly(responseBody);
            throw e;
        }
    }

    DownloadedMedia streamResponseBody(
            InputStream bodyStream, MediaAttachment attachment, String mimeType, long limitBytes) {
        if (limitBytes <= 0) {
            throw OutboundHttpException.policy("Media download has no remaining payload budget");
        }
        Path tempFile = null;
        OutputStream fileOut = null;
        long deadlineNanos = System.nanoTime() + readTimeoutNanos();

        try (InputStream in = bodyStream;
                ContextAwareExecutorService readerExecutor =
                        new ContextAwareExecutorService(Executors.newVirtualThreadPerTaskExecutor())) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[STREAM_BUFFER_SIZE];
            ByteArrayOutputStream initialHeader = new ByteArrayOutputStream(32);
            boolean magicValidated = false;
            long totalRead = 0;
            int n;

            try {
                while (true) {
                    int requestedBytes = totalRead < limitBytes
                            ? (int) Math.min((long) STREAM_BUFFER_SIZE, limitBytes - totalRead)
                            : 1;
                    n = readWithDeadline(in, chunk, requestedBytes, deadlineNanos, readerExecutor);
                    if (n == -1) {
                        break;
                    }
                    if (totalRead >= limitBytes) {
                        throw OutboundHttpException.policy(
                                "Media download exceeded maximum size limit of " + limitBytes + " bytes");
                    }
                    totalRead += n;

                    if (initialHeader.size() < 32) {
                        int headerBytes = Math.min(n, 32 - initialHeader.size());
                        initialHeader.write(chunk, 0, headerBytes);
                        if (initialHeader.size() == 32) {
                            MediaSecurityValidator.validateMagicBytes(initialHeader.toByteArray(), mimeType);
                            magicValidated = true;
                        }
                    }

                    if (tempFile == null) {
                        if (totalRead <= IN_MEMORY_THRESHOLD_BYTES) {
                            buffer.write(chunk, 0, n);
                        } else {
                            tempFile = createTempFile(attachment.attachmentId());
                            fileOut = Files.newOutputStream(tempFile);
                            buffer.writeTo(fileOut);
                            buffer.reset();
                            fileOut.write(chunk, 0, n);
                        }
                    } else {
                        fileOut.write(chunk, 0, n);
                    }
                }

                if (!magicValidated) {
                    MediaSecurityValidator.validateMagicBytes(initialHeader.toByteArray(), mimeType);
                }

                if (fileOut != null) {
                    fileOut.flush();
                    fileOut.close();
                    fileOut = null;
                    return DownloadedMedia.ofTempFile(
                            attachment.attachmentId(),
                            attachment.type(),
                            mimeType,
                            attachment.fileName(),
                            totalRead,
                            tempFile);
                }

                return DownloadedMedia.ofInline(
                        attachment.attachmentId(),
                        attachment.type(),
                        mimeType,
                        attachment.fileName(),
                        buffer.toByteArray());
            } finally {
                if (fileOut != null) {
                    try {
                        fileOut.close();
                    } catch (IOException _) {
                    }
                }
            }
        } catch (OutboundHttpException e) {
            deleteTempFileQuietly(tempFile);
            throw e;
        } catch (IOException e) {
            deleteTempFileQuietly(tempFile);
            throw OutboundHttpException.io("I/O error while streaming media download", e);
        }
    }

    private int readWithDeadline(
            InputStream input,
            byte[] buffer,
            int length,
            long deadlineNanos,
            ContextAwareExecutorService readerExecutor)
            throws IOException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw mediaReadTimeout();
        }

        Future<Integer> readFuture = readerExecutor.submit(() -> input.read(buffer, 0, length));
        try {
            return readFuture.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            readFuture.cancel(true);
            closeQuietly(input);
            readerExecutor.shutdownNow();
            throw mediaReadTimeout();
        } catch (InterruptedException e) {
            readFuture.cancel(true);
            closeQuietly(input);
            Thread.currentThread().interrupt();
            throw OutboundHttpException.io("Media stream reading was interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IOException("Media stream read failed", cause);
        }
    }

    private OutboundHttpException mediaReadTimeout() {
        return OutboundHttpException.timeout(
                "Media stream reading exceeded timeout deadline of " + effectiveReadTimeoutSeconds() + " seconds");
    }

    private static void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException _) {
        }
    }

    private static void deleteTempFileQuietly(Path tempFile) {
        if (tempFile != null) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException _) {
            }
        }
    }

    private Path createTempFile(String attachmentId) throws IOException {
        Path tempDir = Paths.get(dataDir != null ? dataDir : "data", "temp", "media");
        Files.createDirectories(tempDir);
        String safePrefix = attachmentId != null ? attachmentId.replaceAll("[^a-zA-Z0-9_-]", "_") : "media";
        return Files.createTempFile(tempDir, safePrefix + "_" + UUID.randomUUID() + "_", ".tmp");
    }

    private long maxImageSizeBytes() {
        return (long) maxImageSizeMb * 1024L * 1024L;
    }

    private long maxDocSizeBytes() {
        return (long) maxDocSizeMb * 1024L * 1024L;
    }

    private long configuredMaxTotalPayloadBytes() {
        return maxTotalPayloadMb > 0
                ? (long) maxTotalPayloadMb * 1024L * 1024L
                : MediaBudgetLimits.DEFAULT_MAX_TOTAL_PAYLOAD_BYTES;
    }

    private int effectiveConnectTimeoutSeconds() {
        return connectTimeoutSeconds > 0 ? connectTimeoutSeconds : MediaBudgetLimits.DEFAULT_CONNECT_TIMEOUT_SECONDS;
    }

    private int effectiveReadTimeoutSeconds() {
        return readTimeoutSeconds > 0 ? readTimeoutSeconds : MediaBudgetLimits.DEFAULT_READ_TIMEOUT_SECONDS;
    }

    private long readTimeoutNanos() {
        return TimeUnit.SECONDS.toNanos(effectiveReadTimeoutSeconds());
    }
}
