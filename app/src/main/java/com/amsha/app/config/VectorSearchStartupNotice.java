package com.amsha.app.config;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;

import com.amsha.app.cli.CliStartupFailureReporter;
import com.amsha.persistence.api.VectorIndexCompatibility;
import com.amsha.persistence.api.VectorIndexMetadataPort;

import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.StartupEvent;

/** Prints a CLI-visible warning when startup falls back to keyword retrieval. */
@ApplicationScoped
@Unremovable
public class VectorSearchStartupNotice {

    private static final String VECTOR_SEARCH_NOTICE =
            "Vector search is unavailable for one or more indexes. Amsha will continue with keyword/full-text "
                    + "retrieval. Check the diagnostic log for embedding provider or model details.";

    @Inject
    VectorIndexMetadataPort vectorIndexMetadata;

    void onStart(@Observes @Priority(Interceptor.Priority.APPLICATION + 400) StartupEvent event) {
        boolean unavailable = vectorIndexMetadata.compatibilitySnapshot().values().stream()
                .anyMatch(result -> result.status() == VectorIndexCompatibility.Status.UNAVAILABLE);
        if (unavailable) CliStartupFailureReporter.reportNotice(VECTOR_SEARCH_NOTICE, System.err);
    }
}
