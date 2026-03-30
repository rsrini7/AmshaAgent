package com.arcclaw.core.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.arcclaw.core.tools.ToolRegistry;
import io.smallrye.common.annotation.RunOnVirtualThread;

@ApplicationScoped
@Path("/tools")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class ToolRegistryResource {

    @Inject ToolRegistry toolRegistry;

    @GET
    public Response listTools() {
        return Response.ok(
            toolRegistry.listTools()
        ).build();
    }
}
