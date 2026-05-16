package com.arcclaw.app.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.arcclaw.core.config.SystemMetadataRegistry;
import io.smallrye.common.annotation.RunOnVirtualThread;
import org.eclipse.microprofile.openapi.annotations.Operation;

@ApplicationScoped
@Path("/info")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class SystemInfoResource {

    @Inject
    SystemMetadataRegistry registry;

    @GET
    @Operation(summary = "Retrieve system-wide metadata, versions, and capability descriptors")
    public Response getInfo() {
        return Response.ok(registry.toInfo()).build();
    }
}
