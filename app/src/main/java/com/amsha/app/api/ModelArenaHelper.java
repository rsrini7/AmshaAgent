package com.amsha.app.api;

import dev.langchain4j.exception.HttpException;

import jakarta.ws.rs.core.Response;

import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import com.amsha.app.ui.dto.ArenaCompareRequest;
import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.llm.api.AgentChatModelProvider;
import com.amsha.llm.api.ModelDefaults;

/** Helper for model arena validation, error categorization, token math, and defaults. */
final class ModelArenaHelper {

    static final int MAX_INPUT_TOKENS = 2048;
    static final int MAX_OUTPUT_TOKENS = 1024;
    static final int DEFAULT_OUTPUT_TOKENS = 512;
    static final int MAX_MODELS = 3;
    static final long TIMEOUT_SECONDS = 30L;
    static final int MAX_SLOT_LENGTH = 128;

    static final Pattern SLOT_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+(:[a-zA-Z0-9_./:-]+)?$");

    private ModelArenaHelper() {}

    static boolean isProviderAllowed(String cleanProv, Iterable<AgentChatModelProvider> providers) {
        if (cleanProv == null || cleanProv.isBlank()) {
            return false;
        }
        if ("default".equals(cleanProv)) {
            return true;
        }
        if (isClaudeAlias(cleanProv)) {
            return true;
        }
        if (ModelDefaults.isKnownProvider(cleanProv)) {
            return true;
        }
        return findProvider(cleanProv, providers) != null;
    }

    static String validateAndExtractProvider(String slot) {
        return validateAndExtractProvider(slot, null);
    }

    static String validateAndExtractProvider(String slot, Iterable<AgentChatModelProvider> providers) {
        if (slot == null || slot.isBlank()) {
            throw new IllegalArgumentException("Model slot cannot be blank");
        }
        if (slot.length() > MAX_SLOT_LENGTH) {
            throw new IllegalArgumentException("Model slot exceeds the maximum identifier length");
        }
        if (!SLOT_PATTERN.matcher(slot).matches()) {
            throw new IllegalArgumentException("Invalid model slot identifier syntax: " + slot);
        }
        String provider = slot.contains(":") ? slot.substring(0, slot.indexOf(':')) : slot;
        String cleanProv = provider.trim().toLowerCase(Locale.ROOT);
        if (!isProviderAllowed(cleanProv, providers)) {
            throw new IllegalArgumentException("Unallowlisted model slot provider: " + cleanProv);
        }
        if ("claudecode".equals(cleanProv) || "claude".equals(cleanProv)) {
            return ModelDefaults.PROVIDER_CLAUDE_CODE;
        }
        return cleanProv;
    }

    static String categoricalModelSlots(List<String> slots) {
        return categoricalModelSlots(slots, null);
    }

    static String categoricalModelSlots(List<String> slots, Iterable<AgentChatModelProvider> providers) {
        return slots.stream()
                .map(slot -> validateAndExtractProvider(slot, providers))
                .distinct()
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("none");
    }

    static String categorizeError(Throwable t) {
        if (t == null) {
            return "PROVIDER_EXECUTION_ERROR";
        }
        String typedCategory = categorizeTypedError(t);
        if (typedCategory != null) {
            return typedCategory;
        }
        return categorizeErrorMessage(t);
    }

    private static String categorizeTypedError(Throwable t) {
        for (Throwable curr = t; curr != null; curr = curr.getCause()) {
            if (curr instanceof UnsupportedOperationException) {
                return "PROVIDER_UNSUPPORTED_CAPABILITY";
            }
            if (curr instanceof TimeoutException || curr instanceof HttpTimeoutException) {
                return "PROVIDER_TIMEOUT";
            }
            if (curr instanceof HttpException httpEx) {
                int status = httpEx.statusCode();
                if (status == 401 || status == 403) {
                    return "PROVIDER_AUTHENTICATION_ERROR";
                }
                if (status == 429) {
                    return "PROVIDER_RATE_LIMIT";
                }
                if (status == 404) {
                    return "PROVIDER_UNKNOWN_MODEL";
                }
                if (status == 408 || status == 504) {
                    return "PROVIDER_TIMEOUT";
                }
            }
        }
        return null;
    }

    private static String categorizeErrorMessage(Throwable t) {
        String msg = (t.getMessage() != null ? t.getMessage() : "").toLowerCase(Locale.ROOT);
        if (msg.contains("unsupported")) return "PROVIDER_UNSUPPORTED_CAPABILITY";
        if (msg.contains("timeout") || msg.contains("timed out")) return "PROVIDER_TIMEOUT";
        if (msg.contains("401")
                || msg.contains("unauthorized")
                || msg.contains("api_key")
                || msg.contains("api key")
                || msg.contains("invalid key")
                || msg.contains("auth token")
                || msg.contains("authentication")
                || msg.contains("access token")
                || msg.contains("bearer token")) {
            return "PROVIDER_AUTHENTICATION_ERROR";
        }
        if (msg.contains("429") || msg.contains("rate limit") || msg.contains("quota")) return "PROVIDER_RATE_LIMIT";
        if (msg.contains("not found")
                || msg.contains("unknown model")
                || msg.contains("model not found")
                || msg.contains("does not exist")
                || msg.contains("model_not_found")) {
            return "PROVIDER_UNKNOWN_MODEL";
        }
        return "PROVIDER_EXECUTION_ERROR";
    }

    static int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int asciiChars = 0;
        int complexTokens = 0;
        int length = text.length();
        for (int i = 0; i < length; ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp <= 0x7F) {
                asciiChars++;
            } else if (isCjkOrComplexScript(cp)) {
                complexTokens += 2;
            } else {
                complexTokens += 1;
            }
        }
        return (int) Math.ceil(asciiChars / 3.5) + complexTokens;
    }

    static String truncateToTokenBudget(String text, int maxTokens) {
        if (text == null || text.isEmpty() || maxTokens <= 0) {
            return "";
        }
        int asciiChars = 0;
        int complexTokens = 0;
        int length = text.length();
        int lastSafeIndex = 0;
        for (int i = 0; i < length; ) {
            int cp = text.codePointAt(i);
            int next = i + Character.charCount(cp);
            int nextAscii = asciiChars + (cp <= 0x7F ? 1 : 0);
            int nextComplex = complexTokens + (cp <= 0x7F ? 0 : (isCjkOrComplexScript(cp) ? 2 : 1));
            int est = (int) Math.ceil(nextAscii / 3.5) + nextComplex;
            if (est > maxTokens) {
                break;
            }
            asciiChars = nextAscii;
            complexTokens = nextComplex;
            lastSafeIndex = next;
            i = next;
        }
        return text.substring(0, lastSafeIndex);
    }

    private static boolean isCjkOrComplexScript(int cp) {
        return (cp >= 0x2E80 && cp <= 0x9FFF)
                || (cp >= 0xAC00 && cp <= 0xD7AF)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0x1F300 && cp <= 0x1FAFF)
                || (cp >= 0x20000 && cp <= 0x2EBEF)
                || (cp >= 0x0900 && cp <= 0x0D7F);
    }

    static double estimateCostUsd(int inTokens, int outTokens) {
        if (inTokens <= 0 && outTokens <= 0) {
            return 0.0;
        }
        return (inTokens * 0.0000015) + (outTokens * 0.000002);
    }

    static Response validateCompareRequest(ArenaCompareRequest request) {
        return validateCompareRequest(request, null);
    }

    static Response validateCompareRequest(ArenaCompareRequest request, Iterable<AgentChatModelProvider> providers) {
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            return error(Response.Status.BAD_REQUEST, "Prompt is required");
        }
        String prompt = request.prompt();
        int estimatedInTokens = estimateTokens(prompt);
        if (estimatedInTokens > MAX_INPUT_TOKENS) {
            return error(
                    Response.Status.BAD_REQUEST,
                    "Prompt exceeds maximum input budget of " + MAX_INPUT_TOKENS + " tokens (estimated: "
                            + estimatedInTokens + ")");
        }
        List<String> modelSlots = request.models();
        if (modelSlots == null || modelSlots.isEmpty()) {
            return error(Response.Status.BAD_REQUEST, "At least one model slot must be specified");
        }
        if (modelSlots.size() > MAX_MODELS) {
            return error(
                    Response.Status.BAD_REQUEST, "Maximum of " + MAX_MODELS + " models can be compared concurrently");
        }
        for (String slot : modelSlots) {
            try {
                validateAndExtractProvider(slot, providers);
            } catch (IllegalArgumentException _) {
                return error(Response.Status.BAD_REQUEST, "Model slot is not allowlisted");
            }
        }
        return null;
    }

    static AgentChatModelProvider findProvider(String cleanProvider, Iterable<AgentChatModelProvider> providers) {
        if (providers == null || cleanProvider == null) {
            return null;
        }
        AgentChatModelProvider aliasMatch = null;
        for (AgentChatModelProvider p : providers) {
            if (p != null) {
                String actual = p.providerName();
                if (actual != null) {
                    if (actual.equalsIgnoreCase(cleanProvider)) {
                        return p;
                    }
                    if (aliasMatch == null && matchesClaudeAlias(actual, cleanProvider)) {
                        aliasMatch = p;
                    }
                }
            }
        }
        return aliasMatch;
    }

    static boolean matchesClaudeAlias(String a, String b) {
        return isClaudeAlias(a) && isClaudeAlias(b);
    }

    static boolean isClaudeAlias(String name) {
        if (name == null) {
            return false;
        }
        String n = name.trim().toLowerCase(Locale.ROOT);
        return ModelDefaults.PROVIDER_CLAUDE_CODE.equals(n)
                || ModelDefaults.PROVIDER_ANTHROPIC.equals(n)
                || "claudecode".equals(n)
                || "claude".equals(n);
    }

    static String resolveBaseUrl(String providerName, Iterable<AgentChatModelProvider> providers) {
        AgentChatModelProvider p = findProvider(providerName, providers);
        if (p != null) {
            String base = extractBaseUrl(p);
            if (base != null) {
                return base;
            }
        }
        String clean = providerName != null ? providerName.trim().toLowerCase(Locale.ROOT) : "";
        if (isClaudeAlias(clean)) {
            clean = ModelDefaults.PROVIDER_CLAUDE_CODE;
        }
        String defaultUrl = ModelDefaults.defaultBaseUrlForProvider(clean);
        return (defaultUrl != null && !defaultUrl.isBlank()) ? defaultUrl : null;
    }

    static String extractBaseUrl(AgentChatModelProvider p) {
        String base = p != null ? p.baseUrl() : null;
        return (base != null && !base.isBlank() && !"unknown".equalsIgnoreCase(base.trim())) ? base.trim() : null;
    }

    static Response error(Response.Status status, String msg) {
        return Response.status(status).entity(new AmshaErrorResponse(msg)).build();
    }

    static String hashText(String text) {
        if (text == null) return "null";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            return "sha256-error";
        }
    }

    static class ArenaConfigurationException extends RuntimeException {
        ArenaConfigurationException(String msg) {
            super(msg);
        }

        ArenaConfigurationException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    static class ArenaAuditException extends RuntimeException {
        ArenaAuditException(String msg) {
            super(msg);
        }

        ArenaAuditException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
