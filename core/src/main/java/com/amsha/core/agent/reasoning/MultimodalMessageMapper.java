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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import com.amsha.channel.api.AttachmentType;
import com.amsha.channel.api.MediaAttachment;
import com.amsha.common.util.LogUtil;
import com.amsha.llm.api.MultimodalCapability;
import com.amsha.security.api.DownloadedMedia;
import com.amsha.security.api.MediaBudgetLimits;
import com.amsha.security.api.MediaDownloadPort;

import org.jboss.logging.Logger;

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
        String effectivePrompt = textPrompt != null ? textPrompt : "";
        if (attachments == null || attachments.isEmpty()) {
            return UserMessage.from(effectivePrompt);
        }

        MultimodalCapability cap = capability != null ? capability : MultimodalCapability.TEXT_ONLY;
        String model = safeModelName(modelName);
        List<Content> contents = new ArrayList<>();
        contents.add(TextContent.from(effectivePrompt));

        int attachedMediaCount = 0;
        long cumulativeBytes = 0;
        for (MediaAttachment attachment : attachments) {
            if (attachment == null) {
                continue;
            }

            boolean supported = cap.supportsType(attachment.type()) && cap.supportsMimeType(attachment.mimeType());
            if (!supported) {
                byte[] pdfBytes = attachment.type() == AttachmentType.DOCUMENT_PDF
                        ? resolveBytes(attachment, downloadPort, actorUserId, preApproved)
                        : null;
                if (pdfBytes != null) {
                    long pdfSize = pdfBytes.length;
                    if (cumulativeBytes > MediaBudgetLimits.DEFAULT_MAX_TOTAL_PAYLOAD_BYTES - pdfSize) {
                        contents.add(TextContent.from(fallbackCumulativeExceeded(
                                attachment, pdfSize, MediaBudgetLimits.DEFAULT_MAX_TOTAL_PAYLOAD_BYTES)));
                        continue;
                    }
                    cumulativeBytes += pdfSize;
                }
                contents.add(TextContent.from(fallbackUnsupported(attachment, pdfBytes, model)));
                continue;
            }

            // URL-only provider paths cannot safely consume a governed result. Materialize bytes
            // and use base64, or degrade instead of handing the provider an ungoverned URL.
            if (!cap.supportsInlineBase64()) {
                contents.add(TextContent.from(fallbackTransportUnsupported(attachment, model)));
                continue;
            }

            long advisorySize = attachment.sizeBytes() > 0 ? attachment.sizeBytes() : 0L;
            if (cap.maxFileSize() > 0 && advisorySize > cap.maxFileSize()) {
                contents.add(TextContent.from(fallbackOversized(attachment, advisorySize, cap.maxFileSize())));
                continue;
            }
            if (advisorySize > 0
                    && cumulativeBytes > MediaBudgetLimits.DEFAULT_MAX_TOTAL_PAYLOAD_BYTES - advisorySize) {
                contents.add(TextContent.from(fallbackCumulativeExceeded(
                        attachment, advisorySize, MediaBudgetLimits.DEFAULT_MAX_TOTAL_PAYLOAD_BYTES)));
                continue;
            }
            if (cap.maxMediaPerRequest() > 0 && attachedMediaCount >= cap.maxMediaPerRequest()) {
                contents.add(TextContent.from(fallbackCountExceeded(attachment, cap.maxMediaPerRequest())));
                continue;
            }

            byte[] effectiveBytes = resolveBytes(attachment, downloadPort, actorUserId, preApproved);
            if (effectiveBytes == null) {
                contents.add(TextContent.from(fallbackUnavailable(attachment)));
                continue;
            }

            long actualSize = effectiveBytes.length;
            if (cap.maxFileSize() > 0 && actualSize > cap.maxFileSize()) {
                contents.add(TextContent.from(fallbackOversized(attachment, actualSize, cap.maxFileSize())));
                continue;
            }
            if (cumulativeBytes > MediaBudgetLimits.DEFAULT_MAX_TOTAL_PAYLOAD_BYTES - actualSize) {
                contents.add(TextContent.from(fallbackCumulativeExceeded(
                        attachment, actualSize, MediaBudgetLimits.DEFAULT_MAX_TOTAL_PAYLOAD_BYTES)));
                continue;
            }
            cumulativeBytes += actualSize;

            Content mapped = mapSingleAttachment(attachment, effectiveBytes, model);
            if (mapped != null) {
                contents.add(mapped);
                if (!(mapped instanceof TextContent)) {
                    attachedMediaCount++;
                }
            }
        }

        return canonicalizeTextOnly(contents, effectivePrompt);
    }

    private static byte[] resolveBytes(
            MediaAttachment attachment, MediaDownloadPort downloadPort, String userId, boolean preApproved) {
        if (downloadPort == null) {
            return null;
        }
        try (DownloadedMedia downloaded = downloadPort.download(attachment, userId, preApproved)) {
            if (downloaded == null) {
                return null;
            }
            return materialize(downloaded);
        } catch (Exception e) {
            log.warnf("Governed download failed for %s: %s", attachment.attachmentId(), LogUtil.rootMessage(e));
            return null;
        }
    }

    private static byte[] materialize(DownloadedMedia downloaded) throws IOException {
        byte[] inlineData = downloaded.inlineData();
        if (inlineData != null) {
            if (inlineData.length > MediaBudgetLimits.DEFAULT_MAX_TOTAL_PAYLOAD_BYTES) {
                throw new IOException("Downloaded inline media exceeds the turn payload limit");
            }
            return inlineData.clone();
        }

        Path tempFile = downloaded.tempFilePath();
        if (tempFile == null || downloaded.sizeBytes() > MediaBudgetLimits.DEFAULT_MAX_TOTAL_PAYLOAD_BYTES) {
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
                if ((long) bytes.size() + read > MediaBudgetLimits.DEFAULT_MAX_TOTAL_PAYLOAD_BYTES) {
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
                case OTHER -> TextContent.from(fallbackUnsupported(attachment, bytes, modelName));
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

    private static String fallbackUnsupported(MediaAttachment attachment, byte[] bytes, String modelName) {
        String name = sanitizeName(attachment.fileName());
        long sizeBytes = bytes != null ? bytes.length : attachment.sizeBytes();
        String size = formatSize(sizeBytes);
        if (attachment.type() == AttachmentType.DOCUMENT_PDF && bytes != null && bytes.length > 0) {
            String extracted = extractPdfText(bytes, MAX_EXTRACTED_PDF_CHARS);
            if (!extracted.isBlank()) {
                return String.format("[Attached Document: %s - Extracted Text:\n%s]", name, extracted);
            }
        }
        return String.format(
                "[Attached Media: %s (%s, %s) - Model %s does not support %s modality]",
                name, attachment.mimeType(), size, modelName, attachment.type());
    }

    private static String fallbackTransportUnsupported(MediaAttachment attachment, String modelName) {
        return String.format(
                "[Attached Media: %s (%s) - Model %s cannot safely receive governed media bytes]",
                sanitizeName(attachment.fileName()), attachment.mimeType(), modelName);
    }

    private static String fallbackUnavailable(MediaAttachment attachment) {
        return String.format(
                "[Attached Media: %s (%s) - Governed media could not be downloaded or validated]",
                sanitizeName(attachment.fileName()), attachment.mimeType());
    }

    private static String fallbackOversized(MediaAttachment attachment, long actualSize, long maxSize) {
        return String.format(
                "[Attached Media: %s (%s, %s) - Exceeds model maximum supported size of %s]",
                sanitizeName(attachment.fileName()),
                attachment.mimeType(),
                formatSize(actualSize),
                formatSize(maxSize));
    }

    private static String fallbackCumulativeExceeded(MediaAttachment attachment, long actualSize, long maxCumulative) {
        return String.format(
                "[Attached Media: %s (%s, %s) - Cumulative turn media payload exceeded limit of %s]",
                sanitizeName(attachment.fileName()),
                attachment.mimeType(),
                formatSize(actualSize),
                formatSize(maxCumulative));
    }

    private static String fallbackCountExceeded(MediaAttachment attachment, int maxCount) {
        return String.format(
                "[Attached Media: %s (%s) - Exceeds maximum batch media limit of %d items]",
                sanitizeName(attachment.fileName()), attachment.mimeType(), maxCount);
    }

    private static String fallbackError(MediaAttachment attachment) {
        return String.format(
                "[Attached Media: %s (%s) - Extraction and formatting could not be completed]",
                sanitizeName(attachment.fileName()), attachment.mimeType());
    }

    private static String sanitizeName(String name) {
        if (name == null || name.isBlank()) {
            return "unnamed_media";
        }
        String sanitized = name.replaceAll("[\\p{Cntrl}\\r\\n]", "").trim();
        return sanitized.isBlank() ? "unnamed_media" : sanitized;
    }

    private static String safeModelName(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return "active model";
        }
        String sanitized = modelName.replaceAll("[\\p{Cntrl}\\r\\n]", "").trim();
        return sanitized.isBlank() ? "active model" : sanitized;
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "unknown size";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    static String extractPdfText(byte[] pdfBytes, int maxChars) {
        if (pdfBytes == null || pdfBytes.length == 0 || maxChars <= 0) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        String raw = new String(pdfBytes, StandardCharsets.ISO_8859_1);
        int btIndex = 0;
        while ((btIndex = raw.indexOf("BT", btIndex)) != -1 && text.length() < maxChars) {
            int etIndex = raw.indexOf("ET", btIndex + 2);
            if (etIndex == -1) {
                break;
            }
            extractLiteralStrings(raw.substring(btIndex + 2, etIndex), text, maxChars);
            btIndex = etIndex + 2;
        }
        if (text.isEmpty()) {
            extractLiteralStrings(raw, text, maxChars);
        }
        String result = text.toString().replaceAll("[\\r\\n]+", "\n").trim();
        return result.length() > maxChars ? result.substring(0, maxChars) + "..." : result;
    }

    private static void extractLiteralStrings(String block, StringBuilder text, int maxChars) {
        int index = 0;
        while (index < block.length() && text.length() < maxChars) {
            int open = block.indexOf('(', index);
            if (open == -1) {
                break;
            }
            int close = -1;
            int depth = 1;
            for (int i = open + 1; i < block.length(); i++) {
                char current = block.charAt(i);
                if (current == '\\') {
                    i++;
                    continue;
                }
                if (current == '(') {
                    depth++;
                } else if (current == ')' && --depth == 0) {
                    close = i;
                    break;
                }
            }
            if (close == -1) {
                break;
            }
            String literal = block.substring(open + 1, close)
                    .replace("\\(", "(")
                    .replace("\\)", ")")
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\t", "\t")
                    .replace("\\\\", "\\");
            if (isReadableAscii(literal)) {
                text.append(literal).append(' ');
            }
            index = close + 1;
        }
    }

    private static boolean isReadableAscii(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        int printable = 0;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if ((current >= 32 && current <= 126) || Character.isWhitespace(current)) {
                printable++;
            }
        }
        return (double) printable / value.length() >= 0.7;
    }
}
