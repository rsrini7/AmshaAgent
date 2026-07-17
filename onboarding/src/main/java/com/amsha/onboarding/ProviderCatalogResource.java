package com.amsha.onboarding;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.jboss.logging.Logger;

import io.smallrye.common.annotation.RunOnVirtualThread;

@Path("/onboarding/provider-catalog")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@RunOnVirtualThread
public class ProviderCatalogResource {

    private static final Logger log = Logger.getLogger(ProviderCatalogResource.class);
    private String cachedCatalog;

    @GET
    public Response getCatalog() {
        if (cachedCatalog == null) {
            try (InputStream is =
                    Thread.currentThread().getContextClassLoader().getResourceAsStream("provider-catalog.json")) {
                if (is == null) {
                    return Response.status(404)
                            .entity("{\"error\":\"Catalog not found\"}")
                            .build();
                }
                cachedCatalog = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.error("Failed to load provider catalog", e);
                return Response.status(500)
                        .entity("{\"error\":\"Internal error loading catalog\"}")
                        .build();
            }
        }
        return Response.ok(cachedCatalog).build();
    }
}
