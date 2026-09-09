package com.amsha.core.governance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Produces a safe, bounded representation of parameters for approval displays. */
public final class ApprovalParameterSanitizer {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_STRING_LENGTH = 512;
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "authorization",
            "apikey",
            "clientsecret",
            "cookie",
            "credential",
            "headers",
            "password",
            "passphrase",
            "privatekey",
            "secret",
            "setcookie",
            "token");

    private ApprovalParameterSanitizer() {}

    public static String toJson(Map<String, Object> params) {
        try {
            return MAPPER.writeValueAsString(sanitizeMap(params));
        } catch (JsonProcessingException _) {
            return "{}";
        }
    }

    private static Map<String, Object> sanitizeMap(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        params.forEach((key, value) -> sanitized.put(key, sanitizeValue(key, value)));
        return sanitized;
    }

    private static Object sanitizeValue(String key, Object value) {
        if (isSensitiveKey(key)) {
            return "[REDACTED]";
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((nestedKey, nestedValue) ->
                    nested.put(String.valueOf(nestedKey), sanitizeValue(String.valueOf(nestedKey), nestedValue)));
            return nested;
        }
        if (value instanceof List<?> list) {
            List<Object> nested = new ArrayList<>(list.size());
            list.forEach(item -> nested.add(sanitizeValue(key, item)));
            return nested;
        }
        if (value instanceof String text && text.length() > MAX_STRING_LENGTH) {
            return text.substring(0, MAX_STRING_LENGTH) + "… [truncated]";
        }
        return value;
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return SENSITIVE_KEYS.stream().anyMatch(normalized::contains);
    }
}
