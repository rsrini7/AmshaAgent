package com.amsha.onboarding;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

import com.amsha.llm.api.LlmProviderDescriptor;
import com.amsha.llm.api.LlmProviderOnboardingAdapter;

import org.jboss.logging.Logger;

import io.smallrye.common.annotation.RunOnVirtualThread;

@Path("/onboarding/provider-catalog")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@RunOnVirtualThread
public class ProviderCatalogResource {

    private static final Logger log = Logger.getLogger(ProviderCatalogResource.class);

    @Inject
    LlmProviderOnboardingAdapter providerAdapter;

    @GET
    public Response getCatalog() {
        List<LlmProviderDescriptor> descriptors = providerAdapter.descriptors();
        if (descriptors.isEmpty()) {
            log.warnf("Provider catalog requested but no provider descriptors are available");
            return Response.status(404)
                    .entity("{\"error\":\"No provider descriptors available\"}")
                    .build();
        }
        log.infof("Serving provider catalog with %d descriptor(s)", descriptors.size());
        return Response.ok(descriptors).build();
    }
}
