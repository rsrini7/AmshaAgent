package com.amsha.onboarding;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.util.List;

import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.llm.api.LlmProviderDescriptor;
import com.amsha.llm.api.LlmProviderOnboardingAdapter;
import com.amsha.llm.api.ModelEntry;

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

    @Context
    SecurityContext securityContext;

    @GET
    public Response getCatalog() {
        List<LlmProviderDescriptor> descriptors = providerAdapter.descriptors();
        if (descriptors.isEmpty()) {
            log.warnf("Provider catalog requested but no provider descriptors are available");
            return Response.status(404)
                    .entity(new AmshaErrorResponse("Not Found", "No provider descriptors available"))
                    .build();
        }
        log.infof("Serving provider catalog with %d descriptor(s)", descriptors.size());
        return Response.ok(descriptors).build();
    }

    @GET
    @Path("/{providerId}/models")
    public Response getProviderModels(
            @PathParam("providerId") String providerId,
            @QueryParam("baseUrl") String customBaseUrl,
            @HeaderParam("X-Provider-API-Key") String apiKey) {
        if (providerId == null || providerId.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new AmshaErrorResponse("Bad Request", "providerId is required"))
                    .build();
        }
        String actorUserId = requireOwner();
        if (actorUserId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new AmshaErrorResponse("Unauthorized", "Authentication required"))
                    .build();
        }
        List<ModelEntry> models = providerAdapter.discoverModels(providerId, customBaseUrl, apiKey, actorUserId);
        log.infof("Discovered %d models for provider %s", models.size(), providerId);
        return Response.ok(models).build();
    }

    private String requireOwner() {
        return (securityContext != null && securityContext.getUserPrincipal() != null)
                ? securityContext.getUserPrincipal().getName()
                : null;
    }
}
