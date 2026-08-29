package com.amsha.core.routing;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import com.amsha.channel.api.AttachmentType;
import com.amsha.common.util.LogUtil;
import com.amsha.llm.api.MultimodalCapabilities;
import com.amsha.llm.api.MultimodalCapability;

import org.jboss.logging.Logger;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Parses the persisted {@code capabilityJson} from a {@link com.amsha.persistence.api.ModelProfileDto}
 * into an immutable {@link MultimodalCapability}. When the JSON is absent, malformed, or empty, falls
 * back to the documented {@link MultimodalCapabilities#defaultCapabilityForModel} heuristic.
 *
 * <p>The JSON schema follows the {@link MultimodalCapability} contract:
 * <pre>{@code
 * {
 *   "supportedTypes": ["IMAGE", "AUDIO", "DOCUMENT_PDF"],
 *   "supportedMimeTypes": ["image/png", "image/jpeg", "audio/wav"],
 *   "maxFileSize": 10485760,
 *   "maxMediaPerRequest": 5,
 *   "supportsInlineBase64": true,
 *   "supportsMediaUrls": true
 * }
 * }</pre>
 */
public final class CapabilityJsonParser {

    private static final Logger log = Logger.getLogger(CapabilityJsonParser.class);

    private CapabilityJsonParser() {}

    /**
     * Resolves the {@link MultimodalCapability} for a model profile. Tries the persisted
     * {@code capabilityJson} first; falls back to the heuristic when absent or unparseable.
     *
     * @param capabilityJson the raw JSON string from the profile, may be {@code null}
     * @param providerAdapterId the provider adapter id (e.g. "omlx", "gemini")
     * @param modelId the model id (e.g. "Qwen3.8-27B-4bit")
     * @return resolved capability, never {@code null}
     */
    public static MultimodalCapability resolve(String capabilityJson, String providerAdapterId, String modelId) {
        MultimodalCapability parsed = parse(capabilityJson);
        if (parsed != null) {
            return parsed;
        }
        return MultimodalCapabilities.defaultCapabilityForModel(providerAdapterId, modelId);
    }

    /**
     * Parses the raw JSON into a {@link MultimodalCapability}. Returns {@code null} when the JSON
     * is absent, blank, or structurally invalid rather than throwing.
     */
    static MultimodalCapability parse(String capabilityJson) {
        if (capabilityJson == null || capabilityJson.isBlank()) {
            return null;
        }
        try {
            JsonObject json = new JsonObject(capabilityJson);
            Set<AttachmentType> types = parseAttachmentTypes(json.getJsonArray("supportedTypes"));
            Set<String> mimes = parseStringSet(json.getJsonArray("supportedMimeTypes"));
            long maxFileSize = json.getLong("maxFileSize", 0L);
            int maxMedia = json.getInteger("maxMediaPerRequest", 0);
            boolean base64 = json.getBoolean("supportsInlineBase64", false);
            boolean urls = json.getBoolean("supportsMediaUrls", false);
            return new MultimodalCapability(types, mimes, maxFileSize, maxMedia, base64, urls);
        } catch (RuntimeException e) {
            log.warnf(
                    "[CAPABILITY-JSON] Failed to parse capabilityJson; falling back to heuristic: %s",
                    LogUtil.rootMessage(e));
            return null;
        }
    }

    private static Set<AttachmentType> parseAttachmentTypes(JsonArray array) {
        if (array == null || array.isEmpty()) {
            return Set.of();
        }
        Set<AttachmentType> types = EnumSet.noneOf(AttachmentType.class);
        for (int i = 0; i < array.size(); i++) {
            String value = array.getString(i);
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                types.add(AttachmentType.valueOf(value.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                log.debugf("[CAPABILITY-JSON] Unknown attachment type '%s'; skipping", value);
            }
        }
        return types.isEmpty() ? Set.of() : Set.copyOf(types);
    }

    private static Set<String> parseStringSet(JsonArray array) {
        if (array == null || array.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new HashSet<>();
        for (int i = 0; i < array.size(); i++) {
            String value = array.getString(i);
            if (value != null && !value.isBlank()) {
                result.add(value.toLowerCase(Locale.ROOT));
            }
        }
        return result.isEmpty() ? Set.of() : Set.copyOf(result);
    }
}
