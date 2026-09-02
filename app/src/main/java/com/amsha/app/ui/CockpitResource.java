package com.amsha.app.ui;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import com.amsha.core.agent.trace.TraceSymbolicationService;
import com.amsha.core.api.dto.AmshaErrorResponse;

import io.smallrye.common.annotation.RunOnVirtualThread;

@ApplicationScoped
@Path("cockpit")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class CockpitResource {

    @Inject
    TraceSymbolicationService symbolicationService;

    @Context
    SecurityContext securityContext;

    /**
     * Resolves safe symbolication metadata for X-Ray drawer.
     *
     * <p>Enforces {@code isInternal()} authorization boundary at the resource layer,
     * passing the resolved boolean down to {@link TraceSymbolicationService}.
     */
    @GET
    @Path("symbolicate")
    public Response symbolicate(@QueryParam("symbol") String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new AmshaErrorResponse("symbol parameter is required"))
                    .build();
        }

        boolean internal = isInternal();
        return symbolicationService
                .symbolicate(symbol, internal)
                .map(meta -> Response.ok(meta).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND)
                        .entity(new AmshaErrorResponse("Symbol not found in allowlist"))
                        .build());
    }

    boolean isInternal() {
        return securityContext != null && securityContext.isUserInRole("internal");
    }
}
