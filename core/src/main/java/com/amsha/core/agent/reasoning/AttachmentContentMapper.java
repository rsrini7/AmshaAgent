package com.amsha.core.agent.reasoning;

import dev.langchain4j.data.audio.Audio;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.pdf.PdfFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

import com.amsha.channel.api.AttachmentType;
import com.amsha.channel.api.MediaAttachment;
import com.amsha.common.util.LogUtil;
import com.amsha.llm.api.audio.AudioTranscriptionPort;
import com.amsha.llm.api.audio.AudioTranscriptionRequest;
import com.amsha.llm.api.audio.AudioTranscriptionResult;
import com.amsha.security.api.DownloadedMedia;

import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Handles media materialization, binary-to-LangChain4j Content mapping, audio transcription,
 * and structured fallback message formatting for multimodal messaging.
 */
final class AttachmentContentMapper {
    private static final Logger log = Logger.getLogger(AttachmentContentMapper.class);
    private static final int MAX_EXTRACTED_PDF_CHARS = 4000;
    private static final int STREAM_BUFFER_SIZE = 8192;
    private static final int MAX_TRANSCRIPT_CHARS = 12000;
    private static final Duration TRANSCRIPTION_TIMEOUT = Duration.ofSeconds(60);

    private AttachmentContentMapper() {}

    static byte[] materialize(DownloadedMedia downloaded, long maxTotalPayloadBytes) throws IOException {
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

    static Content mapSingleAttachment(MediaAttachment attachment, byte[] bytes, String modelName) {
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

    static TextContent mapUnsupportedAttachment(
            MediaAttachment attachment,
            byte[] mediaBytes,
            AudioTranscriptionPort transcriptionPort,
            String model,
            MeterRegistry meterRegistry) {
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

    static String transcribeAudio(
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

    static String fallbackUnsupported(
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

    static String fallbackTransportUnsupported(MediaAttachment attachment, String modelName) {
        return String.format(
                "[Attached Media: %s (%s) - Model %s cannot safely receive governed media bytes]",
                MultimodalMetrics.sanitizeName(attachment.fileName()), attachment.mimeType(), modelName);
    }

    static String fallbackUnavailable(MediaAttachment attachment) {
        return String.format(
                "[Attached Media: %s (%s) - Governed media could not be downloaded or validated]",
                MultimodalMetrics.sanitizeName(attachment.fileName()), attachment.mimeType());
    }

    static String fallbackOversized(MediaAttachment attachment, long actualSize, long maxSize) {
        return String.format(
                "[Attached Media: %s (%s, %s) - Exceeds model maximum supported size of %s]",
                MultimodalMetrics.sanitizeName(attachment.fileName()),
                attachment.mimeType(),
                MultimodalMetrics.formatSize(actualSize),
                MultimodalMetrics.formatSize(maxSize));
    }

    static String fallbackCumulativeExceeded(MediaAttachment attachment, long actualSize, long maxCumulative) {
        return String.format(
                "[Attached Media: %s (%s, %s) - Cumulative turn media payload exceeded limit of %s]",
                MultimodalMetrics.sanitizeName(attachment.fileName()),
                attachment.mimeType(),
                MultimodalMetrics.formatSize(actualSize),
                MultimodalMetrics.formatSize(maxCumulative));
    }

    static String fallbackCountExceeded(MediaAttachment attachment, int maxCount) {
        return String.format(
                "[Attached Media: %s (%s) - Exceeds maximum batch media limit of %d items]",
                MultimodalMetrics.sanitizeName(attachment.fileName()), attachment.mimeType(), maxCount);
    }

    static String fallbackError(MediaAttachment attachment) {
        return String.format(
                "[Attached Media: %s (%s) - Extraction and formatting could not be completed]",
                MultimodalMetrics.sanitizeName(attachment.fileName()), attachment.mimeType());
    }

    static String extractPdfText(byte[] pdfBytes, int maxChars) {
        return MultimodalMetrics.extractPdfText(pdfBytes, maxChars);
    }
}
