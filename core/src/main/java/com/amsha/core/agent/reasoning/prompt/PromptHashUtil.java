package com.amsha.core.agent.reasoning.prompt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class PromptHashUtil {

    private PromptHashUtil() {}

    public static String sha256(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public static String parseHash(String auditMetadata) {
        if (auditMetadata == null || auditMetadata.isBlank()) return "";
        int idx = auditMetadata.indexOf("\"sha256\":\"");
        if (idx < 0) return "";
        int start = idx + "\"sha256\":\"".length();
        int end = auditMetadata.indexOf("\"", start);
        return end > start ? auditMetadata.substring(start, end) : "";
    }
}
