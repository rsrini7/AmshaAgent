package com.amsha.llm.api;

import dev.langchain4j.data.audio.Audio;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
import dev.langchain4j.data.pdf.PdfFile;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.util.ArrayList;
import java.util.List;

import com.amsha.channel.api.AttachmentType;

/**
 * Request-scoped multimodal carriage, candidate sanitization, and fallback for chat requests.
 *
 * <p>Composite and failover models use this utility before dispatching to each candidate. A
 * candidate that cannot consume a content item receives a text-only representation, while
 * request parameters, message metadata, and all supported content are preserved.</p>
 */
public final class MultimodalRequests {
    private MultimodalRequests() {}

    /** Returns true when a request contains at least one non-text user content item. */
    public static boolean hasMultimodalContent(ChatRequest request) {
        if (request == null || request.messages() == null || request.messages().isEmpty()) {
            return false;
        }
        for (ChatMessage message : request.messages()) {
            if (message instanceof UserMessage user && hasNonTextContent(user)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether a candidate can consume every non-text content item in the
     * request without requiring a placeholder rewrite.
     *
     * <p>Failover wrappers use this as an admission check. A candidate that cannot
     * consume the active request is skipped rather than being called with a
     * degraded request. Text-only requests are compatible with every candidate.</p>
     *
     * @param request request to inspect
     * @param candidateCap candidate capability, or {@link MultimodalCapability#TEXT_ONLY}
     * @return {@code true} when the candidate can consume the request as-is
     */
    public static boolean supportsAllMultimodalContent(ChatRequest request, MultimodalCapability candidateCap) {
        if (!hasMultimodalContent(request)) {
            return true;
        }
        return !requiresRewrite(request, candidateCap != null ? candidateCap : MultimodalCapability.TEXT_ONLY);
    }

    /**
     * Returns a text representation safe for text-only provider serializers.
     * Non-text items are represented by bounded placeholders instead of being silently dropped.
     */
    public static String textOf(UserMessage user, String modelName) {
        if (user == null) {
            return "";
        }
        if (user.hasSingleText()) {
            return user.singleText();
        }
        if (user.contents() == null || user.contents().isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Content content : user.contents()) {
            String part = content instanceof TextContent textContent
                    ? textContent.text()
                    : placeholderFor(content, modelName);
            if (part != null && !part.isBlank()) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(part);
            }
        }
        return text.toString();
    }

    /** Returns a text representation using a generic model label. */
    public static String textOf(UserMessage user) {
        return textOf(user, "text-only model");
    }

    /**
     * Sanitizes a request for one candidate model according to its declared capability.
     *
     * <p>If every materialized content item is supported, the original request is returned
     * unchanged. URL-only items are never treated as materialized at this seam because no governed
     * download proof is carried by a LangChain4j content object. When a rewrite is needed,
     * {@link ChatRequest#toBuilder()} and {@link UserMessage#toBuilder()} keep request parameters
     * and message metadata intact.</p>
     */
    public static ChatRequest forCandidate(
            ChatRequest request, MultimodalCapability candidateCap, String candidateModelName) {
        if (request == null || !hasMultimodalContent(request)) {
            return request;
        }

        MultimodalCapability capability = candidateCap != null ? candidateCap : MultimodalCapability.TEXT_ONLY;
        String modelName = safeModelName(candidateModelName);
        boolean needsRewriting = requiresRewrite(request, capability);
        if (!needsRewriting) {
            return request;
        }

        List<ChatMessage> rewritten = new ArrayList<>(request.messages().size());
        int[] acceptedMediaCount = {0};
        for (ChatMessage message : request.messages()) {
            if (message instanceof UserMessage user && hasNonTextContent(user)) {
                rewritten.add(sanitizeUserMessage(user, capability, modelName, acceptedMediaCount));
            } else {
                rewritten.add(message);
            }
        }
        return request.toBuilder().messages(rewritten).build();
    }

    /** Returns a copy of the request with every non-text item converted to text. */
    public static ChatRequest withoutMultimodalContent(ChatRequest request, String candidateModelName) {
        return forCandidate(request, MultimodalCapability.TEXT_ONLY, candidateModelName);
    }

    private static boolean hasNonTextContent(UserMessage user) {
        if (user == null || user.contents() == null || user.contents().isEmpty()) {
            return false;
        }
        for (Content content : user.contents()) {
            if (!(content instanceof TextContent)) {
                return true;
            }
        }
        return false;
    }

    private static boolean requiresRewrite(ChatRequest request, MultimodalCapability capability) {
        int supportedMediaCount = 0;
        for (ChatMessage message : request.messages()) {
            if (!(message instanceof UserMessage user) || user.contents() == null) {
                continue;
            }
            for (Content content : user.contents()) {
                if (content instanceof TextContent) {
                    continue;
                }
                boolean supported = isSupported(content, capability);
                boolean countExceeded =
                        capability.maxMediaPerRequest() > 0 && supportedMediaCount >= capability.maxMediaPerRequest();
                if (!supported || countExceeded) {
                    return true;
                }
                supportedMediaCount++;
            }
        }
        return false;
    }

    private static boolean isSupported(Content content, MultimodalCapability capability) {
        if (content instanceof ImageContent imageContent) {
            Image image = imageContent.image();
            return image != null
                    && capability.supportsType(AttachmentType.IMAGE)
                    && capability.supportsMimeType(image.mimeType())
                    && supportedRepresentation(image.base64Data(), capability);
        }
        if (content instanceof AudioContent audioContent) {
            Audio audio = audioContent.audio();
            return audio != null
                    && capability.supportsType(AttachmentType.AUDIO)
                    && capability.supportsMimeType(audio.mimeType())
                    && supportedRepresentation(audio.base64Data(), capability)
                    && withinSizeLimit(audio.binaryData(), audio.base64Data(), capability);
        }
        if (content instanceof PdfFileContent pdfContent) {
            PdfFile pdf = pdfContent.pdfFile();
            return pdf != null
                    && capability.supportsType(AttachmentType.DOCUMENT_PDF)
                    && capability.supportsMimeType(pdf.mimeType())
                    && supportedRepresentation(pdf.base64Data(), capability)
                    && withinSizeLimit(null, pdf.base64Data(), capability);
        }
        if (content instanceof VideoContent) {
            return false;
        }
        return false;
    }

    private static boolean supportedRepresentation(String base64Data, MultimodalCapability capability) {
        if (base64Data != null && !base64Data.isBlank() && capability.supportsInlineBase64()) {
            return withinSizeLimit(null, base64Data, capability);
        }
        return false;
    }

    private static boolean withinSizeLimit(byte[] binaryData, String base64Data, MultimodalCapability capability) {
        if (binaryData != null) {
            return capability.maxFileSize() <= 0 || binaryData.length <= capability.maxFileSize();
        }
        if (base64Data == null || base64Data.isBlank()) {
            return false;
        }
        long size = decodedBase64Size(base64Data);
        return size >= 0 && (capability.maxFileSize() <= 0 || size <= capability.maxFileSize());
    }

    private static long decodedBase64Size(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        int length = value.length();
        int firstPadding = value.indexOf('=');
        int dataLength = firstPadding >= 0 ? firstPadding : length;
        int padding = firstPadding >= 0 ? length - firstPadding : 0;
        if (padding > 2 || (padding > 0 && length % 4 != 0)) {
            return -1L;
        }
        for (int i = 0; i < dataLength; i++) {
            if (!isBase64Character(value.charAt(i))) {
                return -1L;
            }
        }
        for (int i = dataLength; i < length; i++) {
            if (value.charAt(i) != '=') {
                return -1L;
            }
        }
        int remainder = dataLength % 4;
        if (remainder == 1 || (padding == 1 && remainder != 3) || (padding == 2 && remainder != 2)) {
            return -1L;
        }
        if (padding > 0) {
            return ((long) length / 4L) * 3L - padding;
        }
        return ((long) dataLength / 4L) * 3L + Math.max(0, remainder - 1);
    }

    private static boolean isBase64Character(char value) {
        return value >= 'A' && value <= 'Z'
                || value >= 'a' && value <= 'z'
                || value >= '0' && value <= '9'
                || value == '+'
                || value == '/';
    }

    private static UserMessage sanitizeUserMessage(
            UserMessage user, MultimodalCapability capability, String modelName, int[] acceptedMediaCount) {
        List<Content> sanitized = new ArrayList<>();
        for (Content content : user.contents()) {
            boolean supported = isSupported(content, capability);
            boolean countExceeded = content instanceof TextContent
                    ? false
                    : capability.maxMediaPerRequest() > 0 && acceptedMediaCount[0] >= capability.maxMediaPerRequest();
            if (content instanceof TextContent || (supported && !countExceeded)) {
                sanitized.add(content);
                if (!(content instanceof TextContent)) {
                    acceptedMediaCount[0]++;
                }
            } else if (countExceeded && supported) {
                sanitized.add(TextContent.from(placeholderForCountExceeded(content, capability, modelName)));
            } else {
                sanitized.add(TextContent.from(placeholderFor(content, modelName)));
            }
        }
        if (sanitized.isEmpty()) {
            sanitized.add(TextContent.from(""));
        }
        if (sanitized.stream().allMatch(TextContent.class::isInstance)) {
            return user.toBuilder()
                    .contents(List.of(TextContent.from(textOf(UserMessage.from(sanitized), modelName))))
                    .build();
        }
        return user.toBuilder().contents(sanitized).build();
    }

    private static String placeholderForCountExceeded(
            Content content, MultimodalCapability capability, String modelName) {
        String type = content != null && content.type() != null ? content.type().name() : "OTHER";
        return String.format(
                "[Attached Media: %s - Model %s media item limit is %d per request]",
                type, safeModelName(modelName), capability.maxMediaPerRequest());
    }

    private static String placeholderFor(Content content, String modelName) {
        String model = safeModelName(modelName);
        if (content instanceof ImageContent imageContent) {
            String mime = imageContent.image() != null && imageContent.image().mimeType() != null
                    ? imageContent.image().mimeType()
                    : "image/unknown";
            return String.format(
                    "[Attached Media: Image (%s) - Model %s does not support vision modality]", mime, model);
        }
        if (content instanceof AudioContent audioContent) {
            String mime = audioContent.audio() != null && audioContent.audio().mimeType() != null
                    ? audioContent.audio().mimeType()
                    : "audio/unknown";
            return String.format(
                    "[Attached Media: Audio (%s) - Model %s does not support audio modality]", mime, model);
        }
        if (content instanceof PdfFileContent) {
            return String.format(
                    "[Attached Document: PDF (application/pdf) - Model %s does not support native PDF modality]",
                    model);
        }
        return String.format(
                "[Attached Media: %s - Model %s does not support this modality]",
                content != null && content.type() != null ? content.type().name() : "OTHER", model);
    }

    private static String safeModelName(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return "candidate model";
        }
        String sanitized = modelName.replaceAll("[\\p{Cntrl}\\r\\n]", "").trim();
        return sanitized.isBlank() ? "candidate model" : sanitized;
    }
}
