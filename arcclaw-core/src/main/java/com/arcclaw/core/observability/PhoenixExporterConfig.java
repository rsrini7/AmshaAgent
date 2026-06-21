package com.arcclaw.core.observability;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

@ApplicationScoped
public class PhoenixExporterConfig {

    private static final Logger log = Logger.getLogger(PhoenixExporterConfig.class);

    private final SdkTracerProvider phoenixTracerProvider;

    @Inject
    public PhoenixExporterConfig(
            @ConfigProperty(name = "arcclaw.observability.phoenix.enabled", defaultValue = "true") boolean enabled,
            @ConfigProperty(
                            name = "arcclaw.observability.phoenix.endpoint",
                            defaultValue = "http://localhost:6006/v1/traces")
                    String endpoint) {
        if (!enabled) {
            log.info("Phoenix direct tracing is disabled");
            this.phoenixTracerProvider = null;
            return;
        }

        log.infof("Phoenix direct tracing enabled — exporting to %s", endpoint);
        OtlpHttpSpanExporter exporter =
                OtlpHttpSpanExporter.builder().setEndpoint(endpoint).build();
        this.phoenixTracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                .build();
    }

    public SdkTracerProvider getTracerProvider() {
        return phoenixTracerProvider;
    }
}
