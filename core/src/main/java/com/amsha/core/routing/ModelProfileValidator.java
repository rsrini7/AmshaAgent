package com.amsha.core.routing;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.amsha.channel.api.AttachmentType;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.persistence.api.ModelProfileDto;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Application-level validator for model profiles and connection drafts.
 *
 * <p>Enforces invariant rules before profile persistence or activation:
 * <ul>
 *   <li>Required non-blank identifiers (id, connectionId, modelId);</li>
 *   <li>Supported {@link StructuredOutputMode} values;</li>
 *   <li>Capability JSON syntax, non-negative limits, and supported attachment types;</li>
 *   <li>Rejection of unsupported modalities such as {@code VIDEO};</li>
 *   <li>Normalized MIME/type pair compatibility;</li>
 *   <li>Non-negative safe limits JSON;</li>
 *   <li>Prohibition of {@code fallbackOrder} on profiles (route ordering belongs to assignments);</li>
 *   <li>Relational integrity supporting one-connection-to-many-profiles (N:1) topology.</li>
 * </ul>
 */
@ApplicationScoped
public class ModelProfileValidator {

    private static final String FIELD_FALLBACK_ORDER = "fallbackOrder";
    private static final String MODALITY_VIDEO = "VIDEO";

    /**
     * Validates a persisted or newly-constructed {@link ModelProfileDto}.
     */
    public ProfileValidationResult validateProfile(ModelProfileDto profile) {
        if (profile == null) {
            return ProfileValidationResult.fail("Model profile must not be null");
        }
        List<String> errors = new ArrayList<>();

        validateRequiredField("id", profile.id(), errors);
        validateRequiredField("connectionId", profile.connectionId(), errors);
        validateRequiredField("modelId", profile.modelId(), errors);

        validateStructuredOutputMode(profile.structuredOutputMode(), errors);
        validateCapabilityJsonString(profile.capabilityJson(), errors);
        validateSafeLimitsJsonString(profile.safeLimitsJson(), errors);

        return errors.isEmpty() ? ProfileValidationResult.ok() : ProfileValidationResult.fail(errors);
    }

    /**
     * Validates a raw model profile draft map (from UI, REST, or import).
     */
    public ProfileValidationResult validateProfileDraft(Map<String, Object> draft) {
        if (draft == null) {
            return ProfileValidationResult.fail("Profile draft must not be null");
        }
        List<String> errors = new ArrayList<>();

        if (draft.containsKey(FIELD_FALLBACK_ORDER)) {
            errors.add(
                    "fallbackOrder is not permitted on model profiles; route order belongs to orderedProfileIds on route assignments");
        }

        validateRequiredDraftField("id", draft.get("id"), errors);
        validateRequiredDraftField("connectionId", draft.get("connectionId"), errors);
        validateRequiredDraftField("modelId", draft.get("modelId"), errors);

        Object modeObj = draft.get("structuredOutputMode");
        if (modeObj != null) {
            if (!(modeObj instanceof String modeStr)) {
                errors.add("structuredOutputMode must be a string");
            } else if (!modeStr.isBlank()) {
                validateStructuredOutputMode(modeStr, errors);
            }
        }

        Object capObj = draft.get("capabilityJson");
        if (capObj != null) {
            if (capObj instanceof String s) {
                validateCapabilityJsonString(s, errors);
            } else if (capObj instanceof Map<?, ?> map) {
                try {
                    validateCapabilityJsonString(new JsonObject(asMap(map)).encode(), errors);
                } catch (Exception _) {
                    errors.add("capabilityJson must be a JSON string or JSON object");
                }
            } else {
                errors.add("capabilityJson must be a JSON string or JSON object");
            }
        }

        Object limObj = draft.get("safeLimitsJson");
        if (limObj != null) {
            if (limObj instanceof String s) {
                validateSafeLimitsJsonString(s, errors);
            } else if (limObj instanceof Map<?, ?> map) {
                try {
                    validateSafeLimitsJsonString(new JsonObject(asMap(map)).encode(), errors);
                } catch (Exception _) {
                    errors.add("safeLimitsJson must be a JSON string or JSON object");
                }
            } else {
                errors.add("safeLimitsJson must be a JSON string or JSON object");
            }
        }

        Object enabledObj = draft.get("enabled");
        if (enabledObj != null && !(enabledObj instanceof Boolean)) {
            errors.add("enabled must be a boolean");
        }

        return errors.isEmpty() ? ProfileValidationResult.ok() : ProfileValidationResult.fail(errors);
    }

    /**
     * Validates capability JSON syntax, supported attachment types, normalized MIME pairs, and limits.
     */
    public ProfileValidationResult validateCapabilityJson(String capabilityJson) {
        List<String> errors = new ArrayList<>();
        validateCapabilityJsonString(capabilityJson, errors);
        return errors.isEmpty() ? ProfileValidationResult.ok() : ProfileValidationResult.fail(errors);
    }

    /**
     * Validates safe limits JSON syntax and ensures all declared numeric bounds are non-negative.
     */
    public ProfileValidationResult validateSafeLimitsJson(String safeLimitsJson) {
        List<String> errors = new ArrayList<>();
        validateSafeLimitsJsonString(safeLimitsJson, errors);
        return errors.isEmpty() ? ProfileValidationResult.ok() : ProfileValidationResult.fail(errors);
    }

    /**
     * Validates a single connection draft map.
     */
    public ProfileValidationResult validateConnectionDraft(Map<String, Object> conn) {
        if (conn == null) {
            return ProfileValidationResult.fail("Connection draft must not be null");
        }
        List<String> errors = new ArrayList<>();
        Object id = conn.get("id");
        if (id != null) {
            if (!(id instanceof String s)) {
                errors.add("id must be a string");
            } else if (s.isBlank()) {
                errors.add("id must not be blank if provided");
            }
        }
        Object providerObj = conn.containsKey("provider") ? conn.get("provider") : conn.get("providerAdapterId");
        if (providerObj == null || !(providerObj instanceof String provider) || provider.isBlank()) {
            errors.add("provider is required and must be a non-blank string");
        }
        Object netJson = conn.get("networkProfileJson");
        if (netJson != null) {
            if (netJson instanceof String s) {
                if (!s.isBlank()) {
                    NetworkProfilePolicyValidator.validateNetworkProfileJson(s, errors);
                }
            } else if (netJson instanceof Map<?, ?> map) {
                try {
                    NetworkProfilePolicyValidator.validateNetworkProfileJson(
                            new JsonObject(asMap(map)).encode(), errors);
                } catch (Exception _) {
                    errors.add("networkProfileJson must be a JSON string or JSON object");
                }
            } else {
                errors.add("networkProfileJson must be a JSON string or JSON object");
            }
        }
        return errors.isEmpty() ? ProfileValidationResult.ok() : ProfileValidationResult.fail(errors);
    }

    /**
     * Validates draft topology: connection drafts, profile drafts, duplicate ID prevention,
     * and N:1 connection sharing integrity.
     */
    public ProfileValidationResult validateDraftTopology(
            List<?> connections, List<?> profiles, Set<String> existingConnectionIds) {
        List<String> errors = new ArrayList<>();
        Set<String> knownConnectionIds = new HashSet<>();
        if (existingConnectionIds != null) {
            knownConnectionIds.addAll(existingConnectionIds);
        }
        validateDraftConnections(connections, knownConnectionIds, errors);
        validateDraftProfiles(profiles, knownConnectionIds, errors);
        return errors.isEmpty() ? ProfileValidationResult.ok() : ProfileValidationResult.fail(errors);
    }

    private void validateDraftConnections(List<?> connections, Set<String> knownConnectionIds, List<String> errors) {
        if (connections == null) {
            return;
        }
        Set<String> seenConnectionIds = new HashSet<>();
        for (Object item : connections) {
            if (!(item instanceof Map<?, ?> map)) {
                errors.add("Connection draft entry must be a non-null JSON object");
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> conn = (Map<String, Object>) map;
            ProfileValidationResult res = validateConnectionDraft(conn);
            if (!res.valid()) {
                errors.addAll(res.errors());
            }
            Object id = conn.get("id");
            if (id instanceof String s && !s.isBlank()) {
                if (!seenConnectionIds.add(s)) {
                    errors.add("Duplicate connection ID across drafts: " + s);
                }
                knownConnectionIds.add(s);
            }
        }
    }

    private void validateDraftProfiles(List<?> profiles, Set<String> knownConnectionIds, List<String> errors) {
        if (profiles == null) {
            return;
        }
        Set<String> seenProfileIds = new HashSet<>();
        for (Object item : profiles) {
            if (!(item instanceof Map<?, ?> map)) {
                errors.add("Profile draft entry must be a non-null JSON object");
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) map;
            ProfileValidationResult pRes = validateProfileDraft(p);
            if (!pRes.valid()) {
                errors.addAll(pRes.errors());
            }
            Object pId = p.get("id");
            if (pId instanceof String s && !s.isBlank() && !seenProfileIds.add(s)) {
                errors.add("Duplicate profile ID across drafts: " + s);
            }
            Object connId = p.get("connectionId");
            if (connId instanceof String s && !s.isBlank() && !knownConnectionIds.contains(s)) {
                String name = (pId instanceof String pidStr && !pidStr.isBlank()) ? pidStr : "<unnamed>";
                errors.add("Profile '" + name + "' references unknown connectionId: " + s);
            }
        }
    }

    // ---- internal validation helpers ----

    private static void validateRequiredField(String fieldName, String value, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add(fieldName + " is required");
        }
    }

    private static void validateRequiredDraftField(String fieldName, Object value, List<String> errors) {
        if (!(value instanceof String s) || s.isBlank()) {
            errors.add(fieldName + " is required");
        }
    }

    private static void validateStructuredOutputMode(String mode, List<String> errors) {
        if (mode == null || mode.isBlank()) {
            return;
        }
        try {
            StructuredOutputMode.valueOf(mode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException _) {
            errors.add(
                    "Invalid structuredOutputMode: " + mode + ". Supported modes: NATIVE, PROMPT_DIRECTIVE, LENIENT");
        }
    }

    private static void validateCapabilityJsonString(String capabilityJson, List<String> errors) {
        if (capabilityJson == null || capabilityJson.isBlank()) {
            return;
        }
        JsonObject json;
        try {
            json = new JsonObject(capabilityJson);
        } catch (Exception _) {
            errors.add("capabilityJson must be valid JSON object");
            return;
        }

        Set<AttachmentType> declaredTypes = new HashSet<>();
        if (json.containsKey("supportedTypes")) {
            declaredTypes.addAll(validateSupportedTypes(json.getValue("supportedTypes"), errors));
        }
        if (json.containsKey("modalities")) {
            validateModalities(json.getValue("modalities"), errors);
        }
        if (json.containsKey("attachments")) {
            validateAttachmentsArray(json.getValue("attachments"), declaredTypes, errors);
        }
        if (json.containsKey("supportedMimeTypes")) {
            validateSupportedMimeTypes(json.getValue("supportedMimeTypes"), declaredTypes, errors);
        }

        Object maxFileVal = json.getValue("maxFileSize");
        if (maxFileVal instanceof Number num && num.longValue() < 0) {
            errors.add("maxFileSize cannot be negative: " + num);
        }
        Object maxMediaVal = json.getValue("maxMediaPerRequest");
        if (maxMediaVal instanceof Number num && num.intValue() < 0) {
            errors.add("maxMediaPerRequest cannot be negative: " + num);
        }
    }

    private static void validateModalities(Object modalitiesObj, List<String> errors) {
        if (!(modalitiesObj instanceof JsonArray array)) {
            if (modalitiesObj != null) {
                errors.add("modalities must be a JSON array");
            }
            return;
        }
        for (int i = 0; i < array.size(); i++) {
            Object val = array.getValue(i);
            if (val == null) continue;
            String modStr = String.valueOf(val).trim();
            if (MODALITY_VIDEO.equalsIgnoreCase(modStr)) {
                errors.add("Unsupported modality: VIDEO is not supported");
            }
        }
    }

    private static void validateAttachmentsArray(
            Object attachmentsObj, Set<AttachmentType> declaredTypes, List<String> errors) {
        if (!(attachmentsObj instanceof JsonArray array)) {
            if (attachmentsObj != null) {
                errors.add("attachments must be a JSON array");
            }
            return;
        }
        for (int i = 0; i < array.size(); i++) {
            Object item = array.getValue(i);
            if (item instanceof JsonObject obj) {
                Object typeObj = obj.getValue("type");
                if (typeObj != null) {
                    String typeStr = String.valueOf(typeObj).trim();
                    if (MODALITY_VIDEO.equalsIgnoreCase(typeStr)) {
                        errors.add("Unsupported modality: VIDEO is not supported");
                    } else {
                        try {
                            AttachmentType type = AttachmentType.valueOf(typeStr.toUpperCase(Locale.ROOT));
                            if (type == AttachmentType.OTHER) {
                                errors.add("Attachment type 'OTHER' is not supported for model profiles: " + typeStr);
                            } else {
                                declaredTypes.add(type);
                            }
                        } catch (IllegalArgumentException _) {
                            errors.add("Unknown or unsupported attachment type: " + typeStr);
                        }
                    }
                }
            } else if (item != null) {
                errors.add("Each attachment entry must be a JSON object");
            }
        }
        for (int i = 0; i < array.size(); i++) {
            Object item = array.getValue(i);
            if (item instanceof JsonObject obj && obj.containsKey("mimeTypes")) {
                validateSupportedMimeTypes(obj.getValue("mimeTypes"), declaredTypes, errors);
            }
        }
    }

    private static Set<AttachmentType> validateSupportedTypes(Object typesObj, List<String> errors) {
        Set<AttachmentType> declaredTypes = new HashSet<>();
        if (!(typesObj instanceof JsonArray array)) {
            if (typesObj != null) {
                errors.add("supportedTypes must be a JSON array");
            }
            return declaredTypes;
        }

        for (int i = 0; i < array.size(); i++) {
            Object val = array.getValue(i);
            if (val == null) continue;
            String typeStr = String.valueOf(val).trim();
            if (MODALITY_VIDEO.equalsIgnoreCase(typeStr)) {
                errors.add("Unsupported modality: VIDEO is not supported");
                continue;
            }
            try {
                AttachmentType type = AttachmentType.valueOf(typeStr.toUpperCase(Locale.ROOT));
                if (type == AttachmentType.OTHER) {
                    errors.add("Attachment type 'OTHER' is not supported for model profiles: " + typeStr);
                } else {
                    declaredTypes.add(type);
                }
            } catch (IllegalArgumentException _) {
                errors.add("Unknown or unsupported attachment type: " + typeStr);
            }
        }
        return declaredTypes;
    }

    private static void validateSupportedMimeTypes(
            Object mimesObj, Set<AttachmentType> declaredTypes, List<String> errors) {
        if (!(mimesObj instanceof JsonArray array)) {
            if (mimesObj != null) {
                errors.add("supportedMimeTypes must be a JSON array");
            }
            return;
        }

        for (int i = 0; i < array.size(); i++) {
            if (errors.size() >= 50) {
                errors.add("Too many capability validation errors; skipping further MIME checks");
                return;
            }
            Object val = array.getValue(i);
            if (val == null) continue;
            String rawMime = String.valueOf(val).trim();
            if (rawMime.isBlank()) continue;
            String cleanMime = rawMime.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (!cleanMime.contains("/") || cleanMime.startsWith("/") || cleanMime.endsWith("/")) {
                errors.add("Invalid MIME type format: " + rawMime);
                continue;
            }
            if (cleanMime.startsWith("video/")) {
                errors.add("Unsupported modality: video MIME types are not supported: " + rawMime);
                continue;
            }
            validateMimeCompatibility(rawMime, cleanMime, declaredTypes, errors);
        }
    }

    private static void validateMimeCompatibility(
            String rawMime, String cleanMime, Set<AttachmentType> declaredTypes, List<String> errors) {
        if (cleanMime.startsWith("image/")) {
            if (!declaredTypes.contains(AttachmentType.IMAGE)) {
                errors.add("MIME type '" + rawMime + "' is incompatible with declared supportedTypes (missing IMAGE)");
            }
        } else if (cleanMime.startsWith("audio/")) {
            if (!declaredTypes.contains(AttachmentType.AUDIO)) {
                errors.add("MIME type '" + rawMime + "' is incompatible with declared supportedTypes (missing AUDIO)");
            }
        } else if ("application/pdf".equals(cleanMime)) {
            if (!declaredTypes.contains(AttachmentType.DOCUMENT_PDF)) {
                errors.add("MIME type '" + rawMime
                        + "' is incompatible with declared supportedTypes (missing DOCUMENT_PDF)");
            }
        } else {
            errors.add("MIME type '" + rawMime + "' is not supported or incompatible with declared supportedTypes");
        }
    }

    private static void validateSafeLimitsJsonString(String safeLimitsJson, List<String> errors) {
        if (safeLimitsJson == null || safeLimitsJson.isBlank()) {
            return;
        }
        JsonObject json;
        try {
            json = new JsonObject(safeLimitsJson);
        } catch (Exception _) {
            errors.add("safeLimitsJson must be valid JSON object");
            return;
        }
        for (String fieldName : json.fieldNames()) {
            Object val = json.getValue(fieldName);
            if (val instanceof Number num && num.doubleValue() < 0) {
                errors.add("safeLimitsJson field '" + fieldName + "' cannot be negative: " + val);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
