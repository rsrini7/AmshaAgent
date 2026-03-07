package com.arcclaw.core.api;

import com.arcclaw.core.config.SystemMetadataRegistry;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
@Path("/info")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class SystemInfoResource {

    @Inject
    SystemMetadataRegistry registry;

    @GET
    public Response getInfo() {
        return Response.ok(registry.toInfo()).build();
    }
}
