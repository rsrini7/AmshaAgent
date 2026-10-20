package com.amsha.app.api.mapper;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.time.Instant;

import com.amsha.common.util.LogUtil;
import com.amsha.core.api.dto.AmshaErrorResponse;

import org.jboss.logging.Logger;

@Provider
public class GlobalExceptionMapper implements ExceptionMapper<Throwable> {

    private static final Logger log = Logger.getLogger(GlobalExceptionMapper.class);

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(Throwable exception) {
        log.errorf(
                LogUtil.relevantThrowable(exception),
                "REST Endpoint Exception [%s]: %s",
                uriInfo.getPath(),
                exception.getMessage());

        if (exception instanceof WebApplicationException wae) {
            return Response.fromResponse(wae.getResponse())
                    .entity(new AmshaErrorResponse(
                            null, "Request Failed", exception.getMessage(), uriInfo.getPath(), Instant.now()))
                    .build();
        }

        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(new AmshaErrorResponse(
                        null, "Internal Server Error", exception.getMessage(), uriInfo.getPath(), Instant.now()))
                .build();
    }
}
