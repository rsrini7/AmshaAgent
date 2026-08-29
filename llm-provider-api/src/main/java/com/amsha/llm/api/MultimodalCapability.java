package com.amsha.llm.api;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import com.amsha.channel.api.AttachmentType;

/**
 * Immutable data contract specifying supported multimodal capabilities per resolved model.
 * Strictly model-specific and data-driven rather than hardcoded provider assumptions.
 */
public record MultimodalCapability(
        Set<AttachmentType> supportedTypes,
        Set<String> supportedMimeTypes,
        long maxFileSize,
        int maxMediaPerRequest,
        boolean supportsInlineBase64,
        boolean supportsMediaUrls) {

    public static final MultimodalCapability TEXT_ONLY =
            new MultimodalCapability(Set.of(), Set.of(), 0L, 0, false, false);

    public MultimodalCapability {
        supportedTypes = supportedTypes != null ? Set.copyOf(supportedTypes) : Set.of();
        supportedMimeTypes = supportedMimeTypes == null
                ? Set.of()
                : supportedMimeTypes.stream()
                        .filter(mime -> mime != null && !mime.isBlank())
                        .map(mime -> mime.split(";", 2)[0].trim().toLowerCase(Locale.ROOT))
                        .filter(mime -> !mime.isBlank())
                        .collect(Collectors.toUnmodifiableSet());
        maxFileSize = Math.max(0L, maxFileSize);
        maxMediaPerRequest = Math.max(0, maxMediaPerRequest);
    }

    public boolean supportsType(AttachmentType type) {
        return type != null && supportedTypes.contains(type);
    }

    public boolean supportsMimeType(String mimeType) {
        if (mimeType == null || supportedMimeTypes.isEmpty()) {
            return false;
        }
        String cleanMime = mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return supportedMimeTypes.contains(cleanMime);
    }

    public boolean isMultimodal() {
        return !supportedTypes.isEmpty();
    }
}
