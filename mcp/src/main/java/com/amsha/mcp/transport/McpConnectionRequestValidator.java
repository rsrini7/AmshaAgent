package com.amsha.mcp.transport;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates remote MCP destinations and caller-supplied HTTP headers. */
final class McpConnectionRequestValidator {
    private static final Pattern HEADER_NAME_PATTERN = Pattern.compile("^[!#$%&'*+\\-.0-9A-Za-z]+$");
    private static final Set<String> FORBIDDEN_HEADERS = Set.of(
            "accept",
            "connection",
            "content-length",
            "content-type",
            "host",
            "keep-alive",
            "mcp-session-id",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade");

    void validateEndpoint(String url) {
        URI uri = URI.create(url);
        if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
            throw new IllegalArgumentException("MCP endpoint URL must not contain user-info credentials");
        }
    }

    List<ValidatedHeader> validateHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return List.of();
        Set<String> names = new HashSet<>();
        return headers.entrySet().stream()
                .map(entry -> validateHeader(entry.getKey(), entry.getValue()))
                .peek(header -> {
                    if (!names.add(header.normalizedName())) {
                        throw new IllegalArgumentException("Duplicate MCP header name: " + header.normalizedName());
                    }
                })
                .toList();
    }

    private ValidatedHeader validateHeader(String name, String value) {
        if (name == null || name.isBlank() || !HEADER_NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid MCP header name: " + name);
        }
        String normalizedName = name.toLowerCase(Locale.ROOT);
        if (FORBIDDEN_HEADERS.contains(normalizedName)) {
            throw new IllegalArgumentException("MCP header transport-owned or forbidden: " + normalizedName);
        }
        if (value == null || value.isBlank() || containsUnsafeHeaderValue(value)) {
            throw new IllegalArgumentException("Invalid MCP header value for: " + normalizedName);
        }
        return new ValidatedHeader(normalizedName, value);
    }

    private static boolean containsUnsafeHeaderValue(String value) {
        if (value.contains("${") || value.contains("$(") || value.contains("`")) return true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7E || c == 0x7F) return true;
        }
        return false;
    }

    record ValidatedHeader(String normalizedName, String value) {}
}
