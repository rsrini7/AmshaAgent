package com.amsha.security.media;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import com.amsha.channel.api.MediaAttachment;
import com.amsha.common.util.LogUtil;
import com.amsha.core.scope.ContextAwareExecutorService;
import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.core.security.http.GovernedHttpClient;
import com.amsha.security.api.DownloadedMedia;
import com.amsha.security.api.OutboundPolicyDecision;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.http.OutboundHttpException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;
import io.vertx.core.Context;

/**
 * Resolves opaque {@code telegram://file/<file_id>} media references through the governed
 * outbound security boundary.
 *
 * <p>Fetches Telegram file metadata via the Bot API {@code getFile} endpoint, strictly validates
 * the returned relative file path to reject arbitrary host redirection or path traversal,
 * and streams the file payload through the governed HTTP transport.</p>
 */
@Unremovable
@ApplicationScoped
class GovernedTelegramMediaResolver {

    @FunctionalInterface
    interface MetadataTransport {
        HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException;
    }

    private static final Logger log = Logger.getLogger(GovernedTelegramMediaResolver.class);
    private static final Pattern TELEGRAM_FILE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,512}$");
    private static final Pattern SAFE_FILE_PATH_PATTERN = Pattern.compile("^[A-Za-z0-9_/.-]{1,1024}$");
    private static final Pattern TELEGRAM_BOT_TOKEN_PATTERN = Pattern.compile("^[0-9A-Za-z_:-]{10,128}$");
    private static final int MAX_GET_FILE_RESPONSE_BYTES = 64 * 1024; // 64 KiB

    @ConfigProperty(name = "amsha.channel.telegram.bot-token")
    Optional<String> telegramBotToken = Optional.empty();

    @ConfigProperty(name = "amsha.channel.telegram.base-uri", defaultValue = "https://api.telegram.org")
    String telegramBaseUri;

    @Inject
    OutboundHttpPolicyService policyService;

    @Inject
    ObjectMapper objectMapper;

    MetadataTransport customMetadataTransport;

    GovernedTelegramMediaResolver() {}

    GovernedTelegramMediaResolver(
            String telegramBotToken,
            String telegramBaseUri,
            OutboundHttpPolicyService policyService,
            ObjectMapper objectMapper) {
        this.telegramBotToken = Optional.ofNullable(telegramBotToken);
        this.telegramBaseUri = telegramBaseUri;
        this.policyService = policyService;
        this.objectMapper = objectMapper;
    }

    /**
     * Resolves and downloads media for a validated {@code telegram://file/<file_id>} reference.
     */
    public DownloadedMedia resolveAndDownload(
            MediaAttachment attachment,
            String actorUserId,
            boolean preApproved,
            long maximumBytes,
            GovernedMediaDownloadService downloadService) {
        Objects.requireNonNull(attachment, "attachment must not be null");
        if (Context.isOnEventLoopThread()) {
            throw new IllegalStateException("GovernedTelegramMediaResolver must not run on a Vert.x event loop thread");
        }

        URI sourceUri = attachment.sourceUri();
        if (sourceUri == null) {
            throw new IllegalArgumentException("MediaAttachment has null sourceUri");
        }

        String fileId = validateTelegramUri(sourceUri);
        String token = requireBotToken();
        URI baseParsed = validateBaseUri(telegramBaseUri);
        String normalizedBase = normalizeBaseUri(telegramBaseUri);

        // Evaluate outbound policy for getFile without placing bot token in the policy request
        String getFilePolicyUrl =
                GovernedMediaDownloadService.sanitizeUrlForPolicy(normalizedBase + "/bot" + token + "/getFile");
        OutboundPolicyDecision getFileDecision = policyService.evaluate(
                getFilePolicyUrl, OutboundPurpose.MEDIA_DOWNLOAD, null, actorUserId, preApproved);
        if (getFileDecision.isDenied()) {
            log.warnf(
                    "Telegram getFile outbound access denied for %s (reason: %s)",
                    baseParsed.getHost(), getFileDecision.reason().orElse("policy"));
            throw OutboundHttpException.policy(
                    "Media download denied: " + getFileDecision.reason().orElse("policy"));
        }
        if (getFileDecision.requiresHitl()) {
            log.infof("Telegram getFile outbound access for %s requires HITL approval", baseParsed.getHost());
            throw OutboundHttpException.hitl(getFileDecision.reason().orElse("approval required"));
        }

        // Fetch and validate Telegram file_path
        String filePath = fetchTelegramFilePath(
                normalizedBase, token, fileId, baseParsed, actorUserId, preApproved, downloadService);

        // Evaluate outbound policy for the file download hop without placing bot token in the policy request
        URI downloadUri = URI.create(normalizedBase + "/file/bot" + token + "/" + filePath);
        String filePolicyUrl = GovernedMediaDownloadService.sanitizeUrlForPolicy(downloadUri.toString());
        OutboundPolicyDecision fileDecision =
                policyService.evaluate(filePolicyUrl, OutboundPurpose.MEDIA_DOWNLOAD, null, actorUserId, preApproved);
        if (fileDecision.isDenied()) {
            log.warnf(
                    "Telegram file download outbound access denied for %s (reason: %s)",
                    baseParsed.getHost(), fileDecision.reason().orElse("policy"));
            throw OutboundHttpException.policy(
                    "Media download denied: " + fileDecision.reason().orElse("policy"));
        }
        if (fileDecision.requiresHitl()) {
            log.infof("Telegram file download outbound access for %s requires HITL approval", baseParsed.getHost());
            throw OutboundHttpException.hitl(fileDecision.reason().orElse("approval required"));
        }

        // Download through the governed streaming pipeline.
        // preApproved is passed through from caller; baseParsed acts as the initial approved origin
        // so same-origin requests retain approval, while any off-origin redirect will be re-evaluated.
        try {
            return downloadService.downloadHttpWithRedirects(
                    downloadUri, attachment, actorUserId, preApproved, baseParsed, maximumBytes, 0);
        } catch (Exception e) {
            throw scrubTokenException(e, token);
        }
    }

    static String validateTelegramUri(URI uri) {
        if (uri == null) {
            throw OutboundHttpException.policy("Telegram URI must not be null");
        }
        if (!"telegram".equalsIgnoreCase(uri.getScheme())) {
            throw OutboundHttpException.policy("Unsupported media download scheme: " + uri.getScheme());
        }
        if (uri.isOpaque()) {
            throw OutboundHttpException.policy("Telegram media URI must be hierarchical: telegram://file/<file_id>");
        }
        if (!"file".equalsIgnoreCase(uri.getHost())) {
            throw OutboundHttpException.policy("Telegram media URI host must be 'file', got: " + uri.getHost());
        }
        if (uri.getUserInfo() != null) {
            throw OutboundHttpException.policy("Telegram media URI must not contain user info");
        }
        if (uri.getPort() != -1) {
            throw OutboundHttpException.policy("Telegram media URI must not specify a port");
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw OutboundHttpException.policy("Telegram media URI must not contain query or fragment");
        }
        String path = uri.getPath();
        if (path == null || !path.startsWith("/")) {
            throw OutboundHttpException.policy("Telegram media URI missing path");
        }
        String fileId = path.substring(1);
        if (fileId.isEmpty() || fileId.contains("/") || fileId.contains("\\") || fileId.contains("..")) {
            throw OutboundHttpException.policy("Telegram media URI contains invalid path traversal or segments");
        }
        if (fileId.chars().anyMatch(Character::isISOControl)) {
            throw OutboundHttpException.policy("Telegram media URI contains control characters");
        }
        if (!TELEGRAM_FILE_ID_PATTERN.matcher(fileId).matches()) {
            throw OutboundHttpException.policy("Telegram fileId format is invalid");
        }
        return fileId;
    }

    private String requireBotToken() {
        String token = telegramBotToken != null ? telegramBotToken.orElse("").trim() : "";
        if (token.isEmpty() || token.startsWith("mock") || token.equals("mock-telegram-token")) {
            throw OutboundHttpException.policy("Telegram media download disabled: no valid bot token configured");
        }
        if (token.contains("/")
                || token.contains("\\")
                || token.contains("?")
                || token.contains("#")
                || token.contains("@")
                || token.chars().anyMatch(Character::isISOControl)
                || !TELEGRAM_BOT_TOKEN_PATTERN.matcher(token).matches()) {
            throw OutboundHttpException.policy("Telegram bot token format is invalid");
        }
        return token;
    }

    static URI validateBaseUri(String rawBaseUri) {
        String base = rawBaseUri != null && !rawBaseUri.isBlank() ? rawBaseUri.trim() : "https://api.telegram.org";
        URI parsed;
        try {
            parsed = URI.create(base);
        } catch (Exception e) {
            throw OutboundHttpException.policy("Invalid Telegram base URI configuration");
        }
        String scheme = parsed.getScheme();
        String host = parsed.getHost();
        if (scheme == null || host == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw OutboundHttpException.policy("Invalid Telegram base URI scheme or host: " + scheme);
        }
        if (parsed.getUserInfo() != null || parsed.getQuery() != null || parsed.getFragment() != null) {
            throw OutboundHttpException.policy("Telegram base URI must not contain credentials, queries, or fragments");
        }
        return parsed;
    }

    static String normalizeBaseUri(String rawBaseUri) {
        String base = rawBaseUri != null && !rawBaseUri.isBlank() ? rawBaseUri.trim() : "https://api.telegram.org";
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static URI policyUri(URI requestUri) {
        return URI.create(GovernedMediaDownloadService.sanitizeUrlForPolicy(requestUri.toString()));
    }

    private String fetchTelegramFilePath(
            String base,
            String token,
            String fileId,
            URI baseParsed,
            String actorUserId,
            boolean preApproved,
            GovernedMediaDownloadService downloadService) {
        URI getFileUri = URI.create(base + "/bot" + token + "/getFile?file_id=" + fileId);
        HttpRequest request = HttpRequest.newBuilder(getFileUri)
                .timeout(Duration.ofSeconds(downloadService.effectiveReadTimeoutSeconds()))
                .GET()
                .build();

        HttpResponse<InputStream> response;
        try {
            // The general outbound policy intentionally permits localhost for local development;
            // media retrieval still applies the stricter SSRF blocklist to its actual destination.
            if (customMetadataTransport == null) {
                MediaSecurityValidator.validateTarget(
                        getFileUri, Duration.ofSeconds(downloadService.effectiveConnectTimeoutSeconds()));
            }
            if (customMetadataTransport != null) {
                response = customMetadataTransport.send(request);
            } else {
                GovernedHttpClient client = GovernedHttpClient.builder(policyService)
                        .purpose(OutboundPurpose.MEDIA_DOWNLOAD)
                        .actor(actorUserId)
                        .preApproved(preApproved)
                        .connectTimeout(Duration.ofSeconds(downloadService.effectiveConnectTimeoutSeconds()))
                        .policyUriMapper(GovernedTelegramMediaResolver::policyUri)
                        .build();
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            }
        } catch (HttpTimeoutException e) {
            log.warnf("Telegram getFile timed out for %s", baseParsed.getHost());
            throw OutboundHttpException.timeout("Telegram getFile timed out");
        } catch (IOException e) {
            RuntimeException scrubbed = scrubTokenException(e, token);
            log.warnf("Telegram getFile I/O failure for %s: %s", baseParsed.getHost(), LogUtil.rootMessage(scrubbed));
            throw OutboundHttpException.io("Telegram getFile I/O failure", scrubbed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warnf("Telegram getFile interrupted for %s: %s", baseParsed.getHost(), LogUtil.rootMessage(e));
            throw OutboundHttpException.io("Telegram getFile interrupted", e);
        } catch (RuntimeException e) {
            throw scrubTokenException(e, token);
        }

        int statusCode = response.statusCode();
        if (statusCode != 200) {
            closeQuietly(response.body());
            throw OutboundHttpException.io("Telegram getFile failed with HTTP status " + statusCode);
        }

        String body = readBoundedString(
                response.body(), MAX_GET_FILE_RESPONSE_BYTES, downloadService.effectiveReadTimeoutSeconds(), token);
        return parseTelegramFilePath(body, objectMapper);
    }

    static String readBoundedString(InputStream in, int maxBytes, int timeoutSeconds, String token) {
        if (in == null) {
            return "";
        }
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        try (in;
                ContextAwareExecutorService readerExecutor =
                        new ContextAwareExecutorService(Executors.newVirtualThreadPerTaskExecutor())) {
            byte[] buf = new byte[1024];
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int totalRead = 0;
            while (true) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw OutboundHttpException.timeout(
                            "Telegram getFile read exceeded deadline of " + timeoutSeconds + " seconds");
                }
                Future<Integer> readFuture = readerExecutor.submit(() -> in.read(buf));
                int n;
                try {
                    n = readFuture.get(remainingNanos, TimeUnit.NANOSECONDS);
                } catch (TimeoutException _) {
                    readFuture.cancel(true);
                    readerExecutor.shutdownNow();
                    throw OutboundHttpException.timeout(
                            "Telegram getFile read timed out after " + timeoutSeconds + " seconds");
                } catch (InterruptedException e) {
                    readFuture.cancel(true);
                    Thread.currentThread().interrupt();
                    throw OutboundHttpException.io("Telegram getFile read interrupted", e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof IOException ioException) throw ioException;
                    if (cause instanceof RuntimeException runtimeException) throw runtimeException;
                    throw new IOException("Telegram getFile read failed", cause);
                }

                if (n == -1) break;
                totalRead += n;
                if (totalRead > maxBytes) {
                    throw OutboundHttpException.policy(
                            "Telegram getFile response exceeds maximum size of " + maxBytes + " bytes");
                }
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw scrubTokenException(e, token);
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException _) {
            }
        }
    }

    static String parseTelegramFilePath(String body, ObjectMapper mapper) {
        if (body == null || body.isBlank()) {
            throw OutboundHttpException.io("Telegram getFile returned empty body");
        }

        String filePath;
        try {
            ObjectMapper effectiveMapper = mapper != null ? mapper : new ObjectMapper();
            JsonNode root = effectiveMapper.readTree(body);
            if (!root.path("ok").asBoolean(false)) {
                throw OutboundHttpException.io("Telegram getFile returned ok=false");
            }
            filePath = root.path("result").path("file_path").asText(null);
        } catch (OutboundHttpException ohe) {
            throw ohe;
        } catch (Exception _) {
            // Do not leak raw response content in exception messages
            throw OutboundHttpException.io("Failed to parse Telegram getFile JSON response");
        }

        if (filePath == null || filePath.isBlank()) {
            throw OutboundHttpException.io("Telegram getFile response missing file_path");
        }

        validateTelegramFilePath(filePath);
        return filePath;
    }

    static void validateTelegramFilePath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw OutboundHttpException.policy("Telegram file_path must not be null or blank");
        }
        if (filePath.contains("://") || filePath.startsWith("//") || filePath.startsWith("/")) {
            throw OutboundHttpException.policy(
                    "Telegram file_path must be a relative path; absolute URLs and hosts are rejected");
        }
        if (filePath.contains("..") || filePath.contains("\\") || filePath.contains("\0")) {
            throw OutboundHttpException.policy("Telegram file_path contains illegal traversal or escape characters");
        }
        if (filePath.chars().anyMatch(Character::isISOControl)) {
            throw OutboundHttpException.policy("Telegram file_path contains control characters");
        }
        if (!SAFE_FILE_PATH_PATTERN.matcher(filePath).matches()) {
            throw OutboundHttpException.policy("Telegram file_path contains invalid characters");
        }
    }

    static RuntimeException scrubTokenException(Throwable ex, String token) {
        if (ex == null) return null;
        String msg = scrubString(ex.getMessage(), token);
        Throwable cause = ex.getCause() != null ? scrubTokenException(ex.getCause(), token) : null;
        RuntimeException scrubbed = ex instanceof com.amsha.core.security.http.OutboundHttpException ohe
                ? new OutboundHttpException(ohe.category(), msg, cause)
                : new RuntimeException(msg, cause);
        for (Throwable suppressed : ex.getSuppressed()) {
            RuntimeException scrubbedSuppressed = scrubTokenException(suppressed, token);
            if (scrubbedSuppressed != null) {
                scrubbed.addSuppressed(scrubbedSuppressed);
            }
        }
        return scrubbed;
    }

    private static String scrubString(String text, String token) {
        if (text == null) return null;
        if (token != null && !token.isBlank() && text.contains(token)) {
            return text.replace(token, "[REDACTED]");
        }
        return text;
    }
}
