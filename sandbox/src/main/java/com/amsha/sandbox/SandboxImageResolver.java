package com.amsha.sandbox;

import java.util.Optional;
import java.util.function.Function;

import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Unified image resolver for Podman sandbox container execution.
 * Precedence:
 * 1. AMSHA_SANDBOX_IMAGE (env / system property)
 * 2. amsha.sandbox.image (config property)
 * 3. AMSHA_SANDBOX_PODMAN_IMAGE (env / system property)
 * 4. amsha.sandbox.podman-image (config property)
 * 5. Default "localhost/amsha-tool-runner:latest"
 */
public final class SandboxImageResolver {

    public static final String DEFAULT_IMAGE = "localhost/amsha-tool-runner:latest";

    private SandboxImageResolver() {}

    public static String resolveImage(
            Function<String, String> envLookup,
            Optional<String> configuredImage,
            Optional<String> configuredPodmanImage) {
        String env1 = envLookup != null ? envLookup.apply("AMSHA_SANDBOX_IMAGE") : getSysOrEnv("AMSHA_SANDBOX_IMAGE");
        if (isValid(env1)) return env1.trim();

        if (configuredImage != null && configuredImage.isPresent() && isValid(configuredImage.get())) {
            return configuredImage.get().trim();
        }

        String env2 = envLookup != null
                ? envLookup.apply("AMSHA_SANDBOX_PODMAN_IMAGE")
                : getSysOrEnv("AMSHA_SANDBOX_PODMAN_IMAGE");
        if (isValid(env2)) return env2.trim();

        if (configuredPodmanImage != null
                && configuredPodmanImage.isPresent()
                && isValid(configuredPodmanImage.get())) {
            return configuredPodmanImage.get().trim();
        }

        return DEFAULT_IMAGE;
    }

    public static String resolveImage(Optional<String> configuredImage, Optional<String> configuredPodmanImage) {
        return resolveImage(null, configuredImage, configuredPodmanImage);
    }

    public static String resolveImage() {
        try {
            var config = ConfigProvider.getConfig();
            Optional<String> img = config.getOptionalValue("amsha.sandbox.image", String.class);
            Optional<String> podmanImg = config.getOptionalValue("amsha.sandbox.podman-image", String.class);
            return resolveImage(img, podmanImg);
        } catch (Exception _) {
            return resolveImage(Optional.empty(), Optional.empty());
        }
    }

    private static String getSysOrEnv(String key) {
        String val = System.getProperty(key);
        if (isValid(val)) return val;
        return System.getenv(key);
    }

    private static boolean isValid(String str) {
        return str != null && !str.isBlank();
    }
}
