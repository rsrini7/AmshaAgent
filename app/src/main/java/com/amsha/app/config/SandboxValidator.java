package com.amsha.app.config;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import com.amsha.core.sandbox.SandboxStrategy;

/**
 * Validates sandbox availability (Podman) and images.
 */
public class SandboxValidator {

    public static void validateSandboxTools(Path binaryDir) {
        String strategyStr = System.getProperty("AMSHA_SANDBOX_STRATEGY", System.getenv("AMSHA_SANDBOX_STRATEGY"));
        SandboxStrategy strategy = SandboxStrategy.fromString(strategyStr);

        if (SandboxStrategy.PODMAN == strategy) {
            boolean podmanReachable = canRun("podman", "info");
            if (!podmanReachable) {
                System.err.println("[Amsha Agent] ⚠️  Podman is not reachable. Code tools will be OFFLINE.");
            } else {
                String image = System.getProperty(
                        "AMSHA_SANDBOX_IMAGE",
                        System.getenv().getOrDefault("AMSHA_SANDBOX_IMAGE", "localhost/amsha-tool-runner:latest"));
                if (!canRun("podman", "image", "inspect", image)) {
                    System.err.println("[Amsha Agent] ⚠️  Podman image '" + image + "' missing.");
                }
            }
        }
    }

    private static boolean canRun(String... cmd) {
        try {
            return new ProcessBuilder(cmd).start().waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception _) {
            return false;
        }
    }
}
