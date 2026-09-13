package com.amsha.core.agent.reasoning;

import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.amsha.channel.api.AttachmentType;
import com.amsha.channel.api.MediaAttachment;
import com.amsha.common.util.LogUtil;
import com.amsha.llm.api.MultimodalCapability;
import com.amsha.llm.api.audio.AudioTranscriptionPort;
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
                contents.add(AttachmentContentMapper.mapUnsupportedAttachment(
                        attachment, batch.resolve(attachment), transcriptionPort, model, meterRegistry));
                continue;
            }

            // URL-only provider paths cannot safely consume a governed result. Materialize bytes
            // and use base64, or degrade instead of handing the provider an ungoverned URL.
            if (!cap.supportsInlineBase64()) {
                MultimodalMetrics.recordIngestion(
                        meterRegistry, attachment.type(), MultimodalMetrics.resolveSource(attachment), "fallback");
                MultimodalMetrics.recordFallback(meterRegistry, "transport_unsupported", attachment.type());
                contents.add(TextContent.from(AttachmentContentMapper.fallbackTransportUnsupported(attachment, model)));
                continue;
            }

            long advisorySize = attachment.sizeBytes() > 0 ? attachment.sizeBytes() : 0L;
            if (cap.maxFileSize() > 0 && advisorySize > cap.maxFileSize()) {
                MultimodalMetrics.recordIngestion(
                        meterRegistry, attachment.type(), MultimodalMetrics.resolveSource(attachment), "fallback");
                MultimodalMetrics.recordFallback(meterRegistry, "oversized", attachment.type());
                contents.add(TextContent.from(
                        AttachmentContentMapper.fallbackOversized(attachment, advisorySize, cap.maxFileSize())));
                continue;
            }
            if (advisorySize > 0 && cumulativeBytes > maxCumulativeBytes - advisorySize) {
                MultimodalMetrics.recordIngestion(
                        meterRegistry, attachment.type(), MultimodalMetrics.resolveSource(attachment), "fallback");
                MultimodalMetrics.recordFallback(meterRegistry, "cumulative_exceeded", attachment.type());
                contents.add(TextContent.from(AttachmentContentMapper.fallbackCumulativeExceeded(
                        attachment, advisorySize, maxCumulativeBytes)));
                continue;
            }
            if (cap.maxMediaPerRequest() > 0 && attachedMediaCount >= cap.maxMediaPerRequest()) {
                MultimodalMetrics.recordIngestion(
                        meterRegistry, attachment.type(), MultimodalMetrics.resolveSource(attachment), "fallback");
                MultimodalMetrics.recordFallback(meterRegistry, "count_exceeded", attachment.type());
                contents.add(TextContent.from(
                        AttachmentContentMapper.fallbackCountExceeded(attachment, cap.maxMediaPerRequest())));
                continue;
            }

            byte[] effectiveBytes = batch.resolve(attachment);
            if (effectiveBytes == null) {
                MultimodalMetrics.recordIngestion(
                        meterRegistry, attachment.type(), MultimodalMetrics.resolveSource(attachment), "fallback");
                MultimodalMetrics.recordFallback(meterRegistry, "unavailable", attachment.type());
                contents.add(TextContent.from(AttachmentContentMapper.fallbackUnavailable(attachment)));
                continue;
            }

            long actualSize = effectiveBytes.length;
            if (cap.maxFileSize() > 0 && actualSize > cap.maxFileSize()) {
                MultimodalMetrics.recordIngestion(
                        meterRegistry, attachment.type(), MultimodalMetrics.resolveSource(attachment), "fallback");
                MultimodalMetrics.recordFallback(meterRegistry, "oversized", attachment.type());
                contents.add(TextContent.from(
                        AttachmentContentMapper.fallbackOversized(attachment, actualSize, cap.maxFileSize())));
                continue;
            }
            if (cumulativeBytes > maxCumulativeBytes - actualSize) {
                MultimodalMetrics.recordIngestion(
                        meterRegistry, attachment.type(), MultimodalMetrics.resolveSource(attachment), "fallback");
                MultimodalMetrics.recordFallback(meterRegistry, "cumulative_exceeded", attachment.type());
                contents.add(TextContent.from(AttachmentContentMapper.fallbackCumulativeExceeded(
                        attachment, actualSize, maxCumulativeBytes)));
                continue;
            }
            cumulativeBytes += actualSize;

            Content mapped = AttachmentContentMapper.mapSingleAttachment(attachment, effectiveBytes, model);
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
                        scope.add(targets.get(i), AttachmentContentMapper.materialize(media, effectiveLimit));
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

    static String extractPdfText(byte[] pdfBytes, int maxChars) {
        return AttachmentContentMapper.extractPdfText(pdfBytes, maxChars);
    }
}
