package com.amsha.localml.onnx;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/** Reports optional ONNX availability without making configured fallback providers unready. */
@Readiness
@ApplicationScoped
public class OnnxRuntimeHealthCheck implements HealthCheck {

    @Inject
    OnnxRuntimeManager runtimeManager;

    @Override
    public HealthCheckResponse call() {
        String state = runtimeManager.isReady() ? "ready" : runtimeManager.unavailableReason();
        return HealthCheckResponse.named("Local ONNX Runtime")
                .up()
                .withData("status", state == null ? "unavailable" : state)
                .build();
    }
}
