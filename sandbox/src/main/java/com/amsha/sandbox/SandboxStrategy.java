package com.amsha.sandbox;

import java.util.Locale;

/**
 * Defines the available isolation strategies for sandbox execution.
 */
public enum SandboxStrategy {
    /**
     * Recommended: Rootless Podman container per tool call.
     */
    PODMAN,

    /**
     * Lightweight Linux namespaces (unshare) via ProcessBuilder.
     */
    NAMESPACE,

    /**
     * Insecure: Direct execution on the host system without isolation.
     * Fallback for development/non-Linux environments without Podman.
     */
    DIRECT,

    /**
     * Sandbox disabled completely. Same as DIRECT but explicitly indicates
     * intent to skip all isolation logic.
     */
    NONE;

    /**
     * Case-insensitive resolution of the strategy from string.
     * Defaults to PODMAN if null, empty, or unrecognized.
     */
    public static SandboxStrategy fromString(String value) {
        if (value == null || value.isBlank()) return PODMAN;
        try {
            return SandboxStrategy.valueOf(value.toUpperCase(Locale.ROOT).trim());
        } catch (IllegalArgumentException e) {
            return PODMAN;
        }
    }
}
