package com.amsha.app.health;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.Map;

import com.amsha.persistence.api.VectorIndexCompatibility;
import com.amsha.persistence.api.VectorIndexMetadataPort;
import com.amsha.persistence.api.VectorIndexNamespace;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

/** Reports vector-index compatibility without making text-search fallback deployments unready. */
@Readiness
@ApplicationScoped
public class VectorIndexCompatibilityHealthCheck implements HealthCheck {

    private final VectorIndexMetadataPort metadata;

    @Inject
    public VectorIndexCompatibilityHealthCheck(VectorIndexMetadataPort metadata) {
        this.metadata = metadata;
    }

    @Override
    public HealthCheckResponse call() {
        Map<VectorIndexNamespace, VectorIndexCompatibility> snapshot = metadata.compatibilitySnapshot();
        boolean degraded = snapshot.values().stream().anyMatch(result -> !result.vectorEnabled());
        String vectorStatus = snapshot.isEmpty() ? "NOT_ACTIVE" : degraded ? "DEGRADED" : "READY";

        HealthCheckResponseBuilder response =
                HealthCheckResponse.named("Vector Index Compatibility").up().withData("vectorStatus", vectorStatus);
        snapshot.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(VectorIndexNamespace::persistentId)))
                .forEach(entry -> response.withData(
                        "namespace." + entry.getKey().persistentId(),
                        entry.getValue().status().name()));
        return response.build();
    }
}
