package com.arcclaw.app.api;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import com.arcclaw.core.agent.reasoning.prompt.PromptConstants;
import com.arcclaw.core.agent.reasoning.prompt.PromptRegistry;
import com.arcclaw.persistence.api.PromptDto;

@Path("/admin/prompts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PromptAdminResource {

    @Inject
    PromptRegistry promptRegistry;

    @Context
    SecurityContext securityContext;

    @GET
    public Response listPrompts() {
        return Response.ok(promptRegistry.list()).build();
    }

    @GET
    @Path("/{id}")
    public Response getPrompt(@PathParam("id") String id) {
        return promptRegistry.getById(id)
            .map(Response::ok)
            .orElse(Response.status(Response.Status.NOT_FOUND))
            .build();
    }

    @PUT
    @Path("/{id}")
    public Response updatePrompt(@PathParam("id") String id, PromptUpdateRequest request) {
        if (!isInternal()) {
            return Response.status(Response.Status.FORBIDDEN).entity("Only internal API keys can modify prompts").build();
        }
        var existing = promptRegistry.getById(id);
        if (existing.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        PromptDto old = existing.get();
        String newContent = request.content() != null ? request.content() : old.content();
        int newVersion = request.version() != null ? request.version()
            : newContent.equals(old.content()) ? old.version() : old.version() + 1;
        promptRegistry.save(new PromptDto(
            id,
            old.roleId(),
            old.sectionKey(),
            newContent,
            newVersion,
            request.status() != null ? request.status() : PromptConstants.STATUS_ACTIVE,
            request.rolloutPercentage() != null ? request.rolloutPercentage() : old.rolloutPercentage(),
            request.auditMetadata() != null ? request.auditMetadata() : old.auditMetadata(),
            old.createdAt(),
            Instant.now()
        ));
        return Response.ok().build();
    }

    @POST
    @Path("/refresh")
    public Response refreshCache() {
        if (!isInternal()) {
            return Response.status(Response.Status.FORBIDDEN).entity("Only internal API keys can refresh prompts").build();
        }
        promptRegistry.refresh();
        return Response.ok().build();
    }

    @DELETE
    @Path("/{id}")
    public Response deletePrompt(@PathParam("id") String id) {
        if (!isInternal()) {
            return Response.status(Response.Status.FORBIDDEN).entity("Only internal API keys can delete prompts").build();
        }
        boolean deleted = promptRegistry.delete(id);
        return deleted ? Response.noContent().build() : Response.status(Response.Status.NOT_FOUND).build();
    }

    @POST
    public Response createPrompt(PromptUpdateRequest request) {
        if (!isInternal()) {
            return Response.status(Response.Status.FORBIDDEN).entity("Only internal API keys can create prompts").build();
        }
        if (request.roleId() == null || request.sectionKey() == null || request.content() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity("roleId, sectionKey, and content are required")
                .build();
        }
        String id = UUID.randomUUID().toString();
        promptRegistry.save(new PromptDto(
            id,
            request.roleId(),
            request.sectionKey(),
            request.content(),
            request.version() != null ? request.version() : 1,
            request.status() != null ? request.status() : "ACTIVE",
            request.rolloutPercentage() != null ? request.rolloutPercentage() : 100,
            request.auditMetadata(),
            Instant.now(),
            Instant.now()
        ));
        return Response.created(URI.create("/admin/prompts/" + id)).build();
    }

    private boolean isInternal() {
        return securityContext != null && securityContext.isUserInRole("internal");
    }

    public record PromptUpdateRequest(
        String roleId,
        String sectionKey,
        String content,
        Integer version,
        String status,
        Integer rolloutPercentage,
        String auditMetadata
    ) {}
}
