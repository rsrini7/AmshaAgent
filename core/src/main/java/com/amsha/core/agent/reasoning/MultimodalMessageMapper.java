package com.amsha.core.agent.reasoning;

import dev.langchain4j.data.audio.Audio;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.pdf.PdfFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.amsha.channel.api.AttachmentType;
import com.amsha.channel.api.MediaAttachment;
import com.amsha.common.util.LogUtil;
import com.amsha.llm.api.MultimodalCapability;
import com.amsha.llm.api.audio.AudioTranscriptionPort;
import com.amsha.llm.api.audio.AudioTranscriptionRequest;
import com.amsha.llm.api.audio.AudioTranscriptionResult;
import com.amsha.security.api.DownloadedMedia;
import com.amsha.security.api.MediaBudgetLimits;
import com.amsha.security.api.MediaDownloadPort;

import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Maps channel-neutral {@link MediaAttachment} objects into LangChain4j {@link Content} items
 * and constructs multimodal {@link UserMessage} instances.
 *
 * <p>All attachment bytes are obtained through the governed {@link MediaDownloadPort}. A provider
 * never receives an original media URL, because that would move SSRF, HITL, MIME, and size
 * enforcement outside Amsha's security boundary. Unsupported or unavailable media becomes one
 * bounded text representation so every downstream provider can consume the request safely.</p>
 */
public final class MultimodalMessageMapper {
    private static final Logger log = Logger.getLogger(MultimodalMessageMapper.class);
    private static final int MAX_EXTRACTED_PDF_CHARS = 4000;
    private static final int STREAM_BUFFER_SIZE = 8192;
    private static final int MAX_TRANSCRIPT_CHARS = 12000;
    private static final Duration TRANSCRIPTION_TIMEOUT = Duration.ofSeconds(60);

    private MultimodalMessageMapper() {}

    /**
     * Builds a user message without a governed media port.
     *
     * <p>Attachments are deliberately degraded because media must not bypass the governed
     * download/validation seam. Call the overload accepting {@link MediaDownloadPort} in a
     * running application.</p>
     */
    public static UserMessage mapToUserMessage(
            String textPrompt, List<MediaAttachment> attachments, MultimodalCapability capability, String modelName) {
        return mapToUserMessage(textPrompt, attachments, capability, modelName, null, null, false);
    }

    /**
     * Builds a user message carrying supported media attachments through a governed download port.
     *
     * <p>Even inline bytes pass through the port so the same MIME, magic-byte, and budget checks
     * apply to channel-provided and URI-backed media. Only validated bytes are materialized into
     * LangChain4j base64 content; raw source URIs are never forwarded.</p>
     */
    public static UserMessage mapToUserMessage(
            String textPrompt,
            List<MediaAttachment> attachments,
            MultimodalCapability capability,
            String modelName,
            MediaDownloadPort downloadPort,
            String actorUserId,
            boolean preApproved) {
        return mapToUserMessage(
                textPrompt, attachments, capability, modelName, downloadPort, actorUserId, preApproved, null);
    }

    /**
     * Builds a user message carrying supported media attachments through a governed download port
     * and records payload-free Micrometer metrics.
     */
    public static UserMessage mapToUserMessage(
            String textPrompt,
            List<MediaAttachment> attachments,
            MultimodalCapability capability,
            String modelName,
            MediaDownloadPort downloadPort,
            String actorUserId,
            boolean preApproved,
            MeterRegistry meterRegistry) {
        return mapToUserMessage(
                textPrompt,
                attachments,
                capability,
                modelName,
                downloadPort,
                actorUserId,
                preApproved,
                null,
                meterRegistry);
    }

    /**
     * Builds a governed multimodal message, transcribing unsupported audio into bounded text
     * when an STT execution port is available.
     */
    public static UserMessage mapToUserMessage(
            String textPrompt,
            List<MediaAttachment> attachments,
            MultimodalCapability capability,
            String modelName,
            MediaDownloadPort downloadPort,
            String actorUserId,
            boolean preApproved,
            AudioTranscriptionPort transcriptionPort,
            MeterRegistry meterRegistry) {
        String effectivePrompt = textPrompt != null ? textPrompt : "";
        if (attachments == null || attachments.isEmpty()) {
            return UserMessage.from(effectivePrompt);
        }

        MultimodalCapability cap = capability != null ? capability : MultimodalCapability.TEXT_ONLY;
        String model = MultimodalMetrics.safeModelName(modelName);
        List<Content> contents = new ArrayList<>();
        contents.add(TextContent.from(effectivePrompt));

        BatchDownloadScope batch = BatchDownloadScope.open(
                attachments, cap, downloadPort, actorUserId, preApproved, transcriptionPort, model);
        long maxCumulativeBytes = batch.maxTotalPayloadBytes();

        int attachedMediaCount = 0;
        long cumulativeBytes = 0;
        for (MediaAttachment attachment : attachments) {
            if (attachment == null) {
                continue;
            }

            boolean supported = cap.supportsType(attachment.type()) && cap.supportsMimeType(attachment.mimeType());
            if (!supported) {
                contents.add(mapUnsupportedAttachment(attachment, batch, transcriptionPort, model, meterRegistry));
                continue;
            }

            // URL-only provider paths cannot safely consume a governed result. Materialize bytes
            // and use base64, or degrade instead of handing the provider an ungoverned URL.
            if (!cap.supportsInlineBase64()) {
                MultimodalMetrics.recordIngestion(
                        meterRegistry, attachment.type(), MultimodalMetrics.resolveSource(attachment), "fallback");
                MultimodalMetrics.recordFallback(meterRegistry, "transport_unsupported", attachment.type());
                contents.add(TextContent.from(fallbackTransportUnsupported(attachment, model)));
                continue;
            }

            long advisorySize = attachment.sizeBytes() > 0 ? attachment.sizeBytes() : 0L;
            if (cap.maxFileSize() > 0 && advisorySize > cap.maxFileSize()) {
                MultimodalMetrics.recordIngestion(
                        meterRegistry, attachment.type(), MultimodalMetrics.resolveSource(attachment), "fallback");
                MultimodalMetrics.recordFallback(meterRegistry, "oversized", attachment.type());
                contents.add(TextContent.from(fallbackOversized(attachment, advisorySize, cap.maxFileSize())));
                continue;
            }
            if (advisorySize > 0 && cumulativeBytes > maxCumulativeBytes - advisorySize) {
                MultimodalMetrics.recordIngestion(
                        meterRegistry, attachment.type(), MultimodalMetrics.resolveSource(attachment), "fallback");
                MultimodalMetrics.recordFallback(meterRegistry, "cumulative_exceeded", attachment.type());
                contents.add(
                        TextContent.from(fallbackCumulativeExceeded(attachment, advisorySize, maxCumulativeBytes)));
                continue;
            }
            if (cap.maxMediaPerRequest() > 0 && attachedMediaCount >= cap.maxMediaPerRequest()) {
                MultimodalMetrics.recordIngestion(
                        meterRegistry, attachment.type(), MultimodalMetrics.resolveSource(attachment), "fallback");
                MultimodalMetrics.recordFallback(meterRegistry, "count_exceeded", attachment.type());
                contents.add(TextContent.from(fallbackCountExceeded(attachment, cap.maxMediaPerRequest())));
                continue;
            }

            byte[] effectiveBytes = batch.resolve(attachment);
            if (effectiveBytes == null) {
                MultimodalMetrics.recordIngestion(
                        meterRegistry, attachment.type(), MultimodalMetrics.resolveSource(attachment), "fallback");
                MultimodalMetrics.recordFallback(meterRegistry, "unavailable", attachment.type());
                contents.add(TextContent.from(fallbackUnavailable(attachment)));
                continue;
            }

            long actualSize = effectiveBytes.length;
            if (cap.maxFileSize() > 0 && actualSize > cap.maxFileSize()) {
                MultimodalMetrics.recordIngestion(
                        meterRegistry, attachment.type(), MultimodalMetrics.resolveSource(attachment), "fallback");
                MultimodalMetrics.recordFallback(meterRegistry, "oversized", attachment.type());
                contents.add(TextContent.from(fallbackOversized(attachment, actualSize, cap.maxFileSize())));
                continue;
            }
            if (cumulativeBytes > maxCumulativeBytes - actualSize) {
                MultimodalMetrics.recordIngestion(
                        meterRegistry, attachment.type(), MultimodalMetrics.resolveSource(attachment), "fallback");
                MultimodalMetrics.recordFallback(meterRegistry, "cumulative_exceeded", attachment.type());
                contents.add(TextContent.from(fallbackCumulativeExceeded(attachment, actualSize, maxCumulativeBytes)));
                continue;
            }
            cumulativeBytes += actualSize;

            Content mapped = mapSingleAttachment(attachment, effectiveBytes, model);
            if (mapped != null) {
                contents.add(mapped);
                if (!(mapped instanceof TextContent)) {
                    attachedMediaCount++;
                    MultimodalMetrics.recordIngestion(
                            meterRegistry, attachment.type(), MultimodalMetrics.resolveSource(attachment), "attached");
                } else {
                    MultimodalMetrics.recordIngestion(
                            meterRegistry, attachment.type(), MultimodalMetrics.resolveSource(attachment), "fallback");
                    String fallbackReason =
                            attachment.type() == AttachmentType.OTHER ? "unsupported_modality" : "extraction_error";
                    MultimodalMetrics.recordFallback(meterRegistry, fallbackReason, attachment.type());
                }
            }
        }

        return canonicalizeTextOnly(contents, effectivePrompt);
    }

    private static TextContent mapUnsupportedAttachment(
            MediaAttachment attachment,
            BatchDownloadScope batch,
            AudioTranscriptionPort transcriptionPort,
            String model,
            MeterRegistry meterRegistry) {
        byte[] mediaBytes = batch.resolve(attachment);
        if (attachment.type() == AttachmentType.AUDIO) {
            String transcript = transcribeAudio(attachment, mediaBytes, transcriptionPort, meterRegistry);
            if (transcript != null) {
                return TextContent.from(transcript);
            }
        }
        byte[] pdfBytes = attachment.type() == AttachmentType.DOCUMENT_PDF ? mediaBytes : null;
        MultimodalMetrics.recordIngestion(
                meterRegistry, attachment.type(), MultimodalMetrics.resolveSource(attachment), "fallback");
        MultimodalMetrics.recordFallback(meterRegistry, "unsupported_modality", attachment.type());
        return TextContent.from(fallbackUnsupported(attachment, pdfBytes, model, meterRegistry));
    }

    private static String transcribeAudio(
            MediaAttachment attachment,
            byte[] mediaBytes,
            AudioTranscriptionPort transcriptionPort,
            MeterRegistry meterRegistry) {
        if (transcriptionPort == null || mediaBytes == null) {
            return null;
        }
        AudioTranscriptionResult result;
        try {
            if (!transcriptionPort.supports(attachment.mimeType(), mediaBytes.length)) {
                log.infof(
                        "Audio transcription port does not support mime=%s bytes=%d for %s",
                        attachment.mimeType(), mediaBytes.length, attachment.attachmentId());
                return null;
            }
            AudioTranscriptionRequest request = AudioTranscriptionRequest.ofInline(
                    attachment.attachmentId(), attachment.mimeType(), mediaBytes, null, TRANSCRIPTION_TIMEOUT);
            result = transcriptionPort.transcribe(request);
        } catch (RuntimeException e) {
            log.warnf("Audio transcription failed for %s: %s", attachment.attachmentId(), LogUtil.rootMessage(e));
            return null;
        }
        if (result == null || result.status() != AudioTranscriptionResult.Status.TRANSCRIBED) {
            String status = result != null ? result.status().name() : "null";
            String reason = result != null ? result.failureReason() : "null";
            log.warnf(
                    "Audio transcription returned status=%s, reason=%s for attachment %s",
                    status, reason, attachment.attachmentId());
            return null;
        }
        String transcript = result.transcript();
        if (transcript == null || transcript.isBlank()) {
            log.warnf("Audio transcription produced empty transcript for attachment %s", attachment.attachmentId());
            return null;
        }
        String bounded = transcript.strip();
        if (bounded.length() > MAX_TRANSCRIPT_CHARS) {
            bounded = bounded.substring(0, MAX_TRANSCRIPT_CHARS).strip();
        }
        log.infof("Audio transcription succeeded for %s (%d chars)", attachment.attachmentId(), bounded.length());
        MultimodalMetrics.recordIngestion(
                meterRegistry, attachment.type(), MultimodalMetrics.resolveSource(attachment), "success");
        return "[Audio transcript: " + bounded + "]";
    }

    private static final class BatchDownloadScope {
        private final Map<MediaAttachment, ArrayDeque<byte[]>> bytesByAttachment;
        private final long maxTotalPayloadBytes;

        private BatchDownloadScope(long maxTotalPayloadBytes) {
            this.bytesByAttachment = new IdentityHashMap<>();
            this.maxTotalPayloadBytes = maxTotalPayloadBytes;
        }

        private static BatchDownloadScope open(
                List<MediaAttachment> attachments,
                MultimodalCapability capability,
                MediaDownloadPort downloadPort,
                String actorUserId,
                boolean preApproved,
                AudioTranscriptionPort transcriptionPort,
                String modelName) {
            long configuredLimit = downloadPort != null
                    ? downloadPort.maxTotalPayloadBytes()
                    : MediaBudgetLimits.DEFAULT_MAX_TOTAL_PAYLOAD_BYTES;
            long effectiveLimit =
                    configuredLimit > 0 ? configuredLimit : MediaBudgetLimits.DEFAULT_MAX_TOTAL_PAYLOAD_BYTES;
            BatchDownloadScope scope = new BatchDownloadScope(effectiveLimit);
            if (downloadPort == null) {
                return scope;
            }

            List<MediaAttachment> targets = new ArrayList<>();
            long plannedBytes = 0;
            for (MediaAttachment attachment : attachments) {
                if (attachment == null) {
                    continue;
                }
                boolean supported = capability.supportsType(attachment.type())
                        && capability.supportsMimeType(attachment.mimeType());
                boolean needsBytes = !supported
                        ? attachment.type() == AttachmentType.DOCUMENT_PDF
                                || (attachment.type() == AttachmentType.AUDIO && transcriptionPort != null)
                        : capability.supportsInlineBase64();
                if (needsBytes
                        && (capability.maxFileSize() <= 0
                                || attachment.sizeBytes() <= 0
                                || attachment.sizeBytes() <= capability.maxFileSize())) {
                    long declaredSize = attachment.sizeBytes();
                    if (declaredSize > 0
                            && (declaredSize > effectiveLimit || plannedBytes > effectiveLimit - declaredSize)) {
                        continue;
                    }
                    targets.add(attachment);
                    if (declaredSize > 0) {
                        plannedBytes += declaredSize;
                    }
                }
            }
            if (targets.isEmpty()) {
                return scope;
            }

            List<DownloadedMedia> downloaded;
            try {
                downloaded = downloadPort.downloadAll(targets, actorUserId, preApproved);
            } catch (Exception e) {
                log.warnf("Governed media batch download failed for %s: %s", modelName, LogUtil.rootMessage(e));
                return scope;
            }
            if (downloaded == null) {
                log.warnf("Governed media batch download returned no results for %s", modelName);
                return scope;
            }
            for (int i = 0; i < downloaded.size(); i++) {
                DownloadedMedia media = downloaded.get(i);
                if (media == null) {
                    continue;
                }
                try (media) {
                    if (i < targets.size()) {
                        scope.add(targets.get(i), materialize(media, effectiveLimit));
                    }
                } catch (Exception e) {
                    String attachmentId = i < targets.size() ? targets.get(i).attachmentId() : "unknown";
                    log.warnf("Governed media materialization failed %s: %s", attachmentId, LogUtil.rootMessage(e));
                }
            }
            return scope;
        }

        private void add(MediaAttachment attachment, byte[] bytes) {
            bytesByAttachment
                    .computeIfAbsent(attachment, _ -> new ArrayDeque<>())
                    .add(bytes);
        }

        private byte[] resolve(MediaAttachment attachment) {
            ArrayDeque<byte[]> queued = bytesByAttachment.get(attachment);
            return queued == null || queued.isEmpty() ? null : queued.removeFirst();
        }

        private long maxTotalPayloadBytes() {
            return maxTotalPayloadBytes;
        }
    }

    private static byte[] materialize(DownloadedMedia downloaded, long maxTotalPayloadBytes) throws IOException {
        byte[] inlineData = downloaded.inlineData();
        if (inlineData != null) {
            if (inlineData.length > maxTotalPayloadBytes) {
                throw new IOException("Downloaded inline media exceeds the turn payload limit");
            }
            return inlineData.clone();
        }

        Path tempFile = downloaded.tempFilePath();
        if (tempFile == null || downloaded.sizeBytes() > maxTotalPayloadBytes) {
            throw new IOException("Downloaded media has no bounded materializable representation");
        }

        long declaredSize = downloaded.sizeBytes();
        if (declaredSize > Integer.MAX_VALUE) {
            throw new IOException("Downloaded media is too large to materialize");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) Math.min(declaredSize, STREAM_BUFFER_SIZE));
        byte[] buffer = new byte[STREAM_BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(tempFile)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                if ((long) bytes.size() + read > maxTotalPayloadBytes) {
                    throw new IOException("Downloaded media exceeds the turn payload limit");
                }
                bytes.write(buffer, 0, read);
            }
        }
        if (bytes.size() != declaredSize) {
            throw new IOException("Downloaded media size changed while materializing");
        }
        return bytes.toByteArray();
    }

    private static Content mapSingleAttachment(MediaAttachment attachment, byte[] bytes, String modelName) {
        try {
            String base64 = Base64.getEncoder().encodeToString(bytes);
            return switch (attachment.type()) {
                case IMAGE ->
                    ImageContent.from(Image.builder()
                            .base64Data(base64)
                            .mimeType(attachment.mimeType())
                            .build());
                case AUDIO ->
                    AudioContent.from(Audio.builder()
                            .base64Data(base64)
                            .mimeType(attachment.mimeType())
                            .build());
                case DOCUMENT_PDF ->
                    PdfFileContent.from(PdfFile.builder().base64Data(base64).build());
                case OTHER -> TextContent.from(fallbackUnsupported(attachment, bytes, modelName, null));
            };
        } catch (Exception e) {
            log.warnf("Failed to map media attachment %s: %s", attachment.attachmentId(), LogUtil.rootMessage(e));
            return TextContent.from(fallbackError(attachment));
        }
    }

    private static UserMessage canonicalizeTextOnly(List<Content> contents, String fallbackPrompt) {
        if (contents.stream().allMatch(TextContent.class::isInstance)) {
            String text = contents.stream()
                    .map(content -> ((TextContent) content).text())
                    .filter(value -> value != null && !value.isBlank())
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse(fallbackPrompt);
            return UserMessage.from(TextContent.from(text));
        }
        return UserMessage.from(contents);
    }

    private static String fallbackUnsupported(
            MediaAttachment attachment, byte[] bytes, String modelName, MeterRegistry meterRegistry) {
        String name = MultimodalMetrics.sanitizeName(attachment.fileName());
        long sizeBytes = bytes != null ? bytes.length : attachment.sizeBytes();
        String size = MultimodalMetrics.formatSize(sizeBytes);
        if (attachment.type() == AttachmentType.DOCUMENT_PDF && bytes != null && bytes.length > 0) {
            long extractStart = System.nanoTime();
            String extracted = MultimodalMetrics.extractPdfText(bytes, MAX_EXTRACTED_PDF_CHARS);
            boolean success = !extracted.isBlank();
            MultimodalMetrics.recordExtraction(
                    meterRegistry, System.nanoTime() - extractStart, extracted.length(), success);
            if (success) {
                return String.format("[Attached Document: %s - Extracted Text:\n%s]", name, extracted);
            }
        }
        String guidance = attachment.type() == AttachmentType.AUDIO
                ? ". Select an audio-capable model, such as Gemini, to transcribe it."
                : "";
        return String.format(
                "[Attached Media: %s (%s, %s) - Model %s does not support %s modality%s]",
                name, attachment.mimeType(), size, modelName, attachment.type(), guidance);
    }

    private static String fallbackTransportUnsupported(MediaAttachment attachment, String modelName) {
        return String.format(
                "[Attached Media: %s (%s) - Model %s cannot safely receive governed media bytes]",
                MultimodalMetrics.sanitizeName(attachment.fileName()), attachment.mimeType(), modelName);
    }

    private static String fallbackUnavailable(MediaAttachment attachment) {
        return String.format(
                "[Attached Media: %s (%s) - Governed media could not be downloaded or validated]",
                MultimodalMetrics.sanitizeName(attachment.fileName()), attachment.mimeType());
    }

    private static String fallbackOversized(MediaAttachment attachment, long actualSize, long maxSize) {
        return String.format(
                "[Attached Media: %s (%s, %s) - Exceeds model maximum supported size of %s]",
                MultimodalMetrics.sanitizeName(attachment.fileName()),
                attachment.mimeType(),
                MultimodalMetrics.formatSize(actualSize),
                MultimodalMetrics.formatSize(maxSize));
    }

    private static String fallbackCumulativeExceeded(MediaAttachment attachment, long actualSize, long maxCumulative) {
        return String.format(
                "[Attached Media: %s (%s, %s) - Cumulative turn media payload exceeded limit of %s]",
                MultimodalMetrics.sanitizeName(attachment.fileName()),
                attachment.mimeType(),
                MultimodalMetrics.formatSize(actualSize),
                MultimodalMetrics.formatSize(maxCumulative));
    }

    private static String fallbackCountExceeded(MediaAttachment attachment, int maxCount) {
        return String.format(
                "[Attached Media: %s (%s) - Exceeds maximum batch media limit of %d items]",
                MultimodalMetrics.sanitizeName(attachment.fileName()), attachment.mimeType(), maxCount);
    }

    private static String fallbackError(MediaAttachment attachment) {
        return String.format(
                "[Attached Media: %s (%s) - Extraction and formatting could not be completed]",
                MultimodalMetrics.sanitizeName(attachment.fileName()), attachment.mimeType());
    }

    static String extractPdfText(byte[] pdfBytes, int maxChars) {
        return MultimodalMetrics.extractPdfText(pdfBytes, maxChars);
    }
}
