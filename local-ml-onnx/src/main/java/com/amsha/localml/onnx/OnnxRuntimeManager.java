package com.amsha.localml.onnx;

import ai.onnxruntime.OrtEnvironment;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/** Owns feature readiness for the shared ONNX Runtime environment. */
@ApplicationScoped
public class OnnxRuntimeManager {
    private static final Logger log = Logger.getLogger(OnnxRuntimeManager.class);

    @ConfigProperty(name = "amsha.onnx.enabled", defaultValue = "false")
    boolean enabled;

    private volatile OrtEnvironment environment;
    private volatile String unavailableReason;

    @PostConstruct
    void initialize() {
        if (!enabled) {
            unavailableReason = "disabled";
            log.info("ONNX Runtime disabled");
            return;
        }
        try {
            environment = OrtEnvironment.getEnvironment();
            log.infof("ONNX Runtime ready: version=%s", environment.getVersion());
        } catch (RuntimeException | LinkageError e) {
            unavailableReason = e.getClass().getSimpleName();
            log.warnf("ONNX Runtime unavailable: %s", e.getMessage());
        }
    }

    @PreDestroy
    void close() {
        OrtEnvironment environmentToClose = environment;
        environment = null;
        if (environmentToClose == null) return;
        try {
            environmentToClose.close();
            log.info("ONNX Runtime environment closed");
        } catch (RuntimeException | LinkageError e) {
            log.warnf("ONNX Runtime environment cleanup failed: %s", e.getMessage());
        }
    }

    public Optional<OrtEnvironment> environment() {
        return Optional.ofNullable(environment);
    }

    public boolean isReady() {
        return environment != null;
    }

    public String unavailableReason() {
        return unavailableReason;
    }
}
