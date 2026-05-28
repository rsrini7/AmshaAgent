package com.arcclaw.core.sandbox;

import com.arcclaw.core.exception.ArcClawException;

/**
 * Thrown by {@link PodmanContainerExecutor} when the required sandbox
 * image is absent from local Podman storage.
 * <p>
 * Caught by {@link ProcessIsolationExecutor} and converted to
 * {@link SandboxResult#infraFailure(String)} - never propagates to callers
 * above the sandbox layer.
 */
public class SandboxImageMissingException extends ArcClawException {

    public SandboxImageMissingException(String imageName) {
        super("Missing sandbox image: " + imageName);
    }
}
