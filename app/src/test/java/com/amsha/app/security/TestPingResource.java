package com.amsha.app.security;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.smallrye.common.annotation.RunOnVirtualThread;

@Path("/test/ping")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class TestPingResource {

    @GET
    public Response pingGet() {
        return Response.ok("{\"status\":\"ok\"}").build();
    }

    @POST
    public Response pingPost() {
        return Response.status(Response.Status.CREATED)
                .entity("{\"status\":\"created\"}")
                .build();
    }
}
