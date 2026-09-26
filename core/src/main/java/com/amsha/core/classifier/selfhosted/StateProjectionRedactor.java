package com.amsha.core.classifier.selfhosted;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.amsha.classifier.api.RedactionPolicy;
import com.amsha.classifier.api.StateProjectionPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Projects and redacts state before crossing separate-process provider boundaries (§7.4, §7.5).
 * Enforces allow-listed projection: excluded fields are completely dropped from serialization.
 * Applies {@link RedactionPolicy} to redact PII, credentials, secrets, and masked fields.
 * Honors dot-delimited field paths (e.g. parameters.command) in maskedFields.
 * This boundary-specific redactor complements {@link com.amsha.core.agent.trace.TraceRedactor} and
 * {@link com.amsha.core.reflex.PiiScanReflex}; keep sensitive-pattern coverage aligned when updating them.
 * Missing or invalid policies fail closed.
 */
public final class StateProjectionRedactor {

    public static final String REDACTED_MARKER = "[REDACTED]";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final Set<String> SENSITIVE_KEY_SUBSTRINGS = Set.of(
            "apikey",
            "api_key",
            "authorization",
            "bearer",
            "clientsecret",
            "client_secret",
            "cookie",
            "credential",
            "password",
            "passphrase",
            "privatekey",
            "private_key",
            "secret",
            "token");

    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9\\-._~+/]+=*"),
            Pattern.compile("\\b(?:sk|ghp|xoxb|xoxp|xoxs|github_pat)[-_][A-Za-z0-9_-]{12,}\\b"),
            Pattern.compile("\\bAKIA[0-9A-Z]{12,}\\b"),
            Pattern.compile("\\bAIza[0-9A-Za-z_-]{20,}\\b"),
            Pattern.compile(
                    "(?i)(?:api[_-]?key|access[_-]?token|client[_-]?secret|password|secret)\\s*[:=]\\s*[\"']?[^\"'\\s,;]+[\"']?"));

    private static final List<Pattern> PII_PATTERNS = List.of(
            Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"),
            Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"),
            Pattern.compile("\\b(?:\\+?\\d{1,3}[-\\s.]?)?\\(?\\d{3}\\)?[-\\s.]?\\d{3}[-\\s.]?\\d{4}\\b"));

    private final ObjectMapper mapper;

    public StateProjectionRedactor(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Projects and redacts the raw state JSON string according to the specified policy.
     *
     * @param rawStateJson raw JSON state string
     * @param policy projection and redaction policy
     * @return serialized JSON containing exclusively allow-listed and redacted fields
     * @throws IllegalStateException if policy or redaction policy is null/invalid
     * @throws IllegalArgumentException if rawStateJson cannot be parsed as a JSON map
     */
    public String projectAndRedact(String rawStateJson, StateProjectionPolicy policy) {
        validatePolicy(policy);

        if (rawStateJson == null || rawStateJson.isBlank()) {
            throw new IllegalArgumentException("State must be a non-blank JSON object");
        }

        Map<String, Object> rawMap;
        try {
            rawMap = mapper.readValue(rawStateJson, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("State projection input must be a valid JSON object", e);
        }

        Map<String, Object> projected = projectMap(rawMap, policy);
        try {
            return mapper.writeValueAsString(projected);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize projected state", e);
        }
    }

    /**
     * Projects and redacts a map according to the policy.
     *
     * @param rawMap raw state map
     * @param policy projection and redaction policy
     * @return projected and redacted map
     */
    public Map<String, Object> projectMap(Map<String, Object> rawMap, StateProjectionPolicy policy) {
        validatePolicy(policy);
        if (rawMap == null || rawMap.isEmpty()) {
            return Map.of();
        }

        Set<String> allowed = policy.allowedFields();
        RedactionPolicy redaction = policy.redactionPolicy();
        Map<String, Object> result = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : rawMap.entrySet()) {
            String key = entry.getKey();
            if (key == null || !allowed.contains(key)) {
                // Drop excluded fields completely
                continue;
            }
            result.put(key, redactValue(key, key, entry.getValue(), redaction));
        }

        return result;
    }

    private Object redactValue(String path, String key, Object value, RedactionPolicy redaction) {
        if (value == null) {
            return null;
        }

        if (isMasked(path, key, redaction)) {
            return REDACTED_MARKER;
        }

        if (isSensitiveKey(key)) {
            return REDACTED_MARKER;
        }

        if (value instanceof String text) {
            return redactText(text, redaction);
        }

        if (value instanceof Map<?, ?> nestedMap) {
            Map<String, Object> sanitizedNested = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : nestedMap.entrySet()) {
                String subKey = String.valueOf(entry.getKey());
                String childPath = path.isEmpty() ? subKey : path + "." + subKey;
                sanitizedNested.put(subKey, redactValue(childPath, subKey, entry.getValue(), redaction));
            }
            return sanitizedNested;
        }

        if (value instanceof List<?> list) {
            List<Object> sanitizedList = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                String itemPath = path + "[" + i + "]";
                sanitizedList.add(redactValue(itemPath, key, list.get(i), redaction));
            }
            return sanitizedList;
        }

        return value;
    }

    private boolean isMasked(String path, String key, RedactionPolicy redaction) {
        Set<String> masked = redaction.maskedFields();
        return masked.contains(path) || masked.contains(key);
    }

    private String redactText(String text, RedactionPolicy redaction) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String result = text;
        if (redaction.redactSecrets()) {
            for (Pattern pattern : SECRET_PATTERNS) {
                result = pattern.matcher(result).replaceAll(REDACTED_MARKER);
            }
        }
        if (redaction.redactPii()) {
            for (Pattern pattern : PII_PATTERNS) {
                result = pattern.matcher(result).replaceAll(REDACTED_MARKER);
            }
        }
        return result;
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String normalized = key.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return SENSITIVE_KEY_SUBSTRINGS.stream().anyMatch(normalized::contains);
    }

    private static void validatePolicy(StateProjectionPolicy policy) {
        if (policy == null) {
            throw new IllegalStateException("StateProjectionPolicy must not be null; evaluation must fail closed");
        }
        if (policy.redactionPolicy() == null) {
            throw new IllegalStateException("RedactionPolicy must not be null; evaluation must fail closed");
        }
        if (policy.allowedFields() == null || policy.allowedFields().isEmpty()) {
            throw new IllegalStateException(
                    "Allow-listed state projection must not be empty; evaluation must fail closed");
        }
    }
}
