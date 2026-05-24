package com.arcclaw.app.api;

import java.time.Instant;
import java.util.List;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.arcclaw.core.agent.reasoning.prompt.PromptRegistry;
import com.arcclaw.persistence.api.PromptDto;

@Path("/admin/prompts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PromptAdminResource {

    @Inject
    PromptRegistry promptRegistry;

    @GET
    public List<PromptDto> listPrompts() {
        return promptRegistry.list();
    }

    @GET
    @Path("/{id}")
    public Response getPrompt(@PathParam("id") String id) {
        return promptRegistry.list().stream()
            .filter(p -> p.id().equals(id))
            .findFirst()
            .map(Response::ok)
            .orElse(Response.status(Response.Status.NOT_FOUND))
            .build();
    }

    @PUT
    @Path("/{id}")
    public Response updatePrompt(@PathParam("id") String id, PromptUpdateRequest request) {
        var existing = promptRegistry.list().stream()
            .filter(p -> p.id().equals(id))
            .findFirst();
        if (existing.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        PromptDto old = existing.get();
        promptRegistry.save(new PromptDto(
            id,
            old.roleId(),
            old.sectionKey(),
            request.content() != null ? request.content() : old.content(),
            request.version() != null ? request.version() : old.version(),
            request.status() != null ? request.status() : old.status(),
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
        promptRegistry.refresh();
        return Response.ok().build();
    }

    @DELETE
    @Path("/{id}")
    public Response deletePrompt(@PathParam("id") String id) {
        boolean deleted = promptRegistry.delete(id);
        return deleted ? Response.noContent().build() : Response.status(Response.Status.NOT_FOUND).build();
    }

    @POST
    public Response createPrompt(PromptUpdateRequest request) {
        if (request.roleId() == null || request.sectionKey() == null || request.content() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity("roleId, sectionKey, and content are required")
                .build();
        }
        promptRegistry.save(new PromptDto(
            UUID.randomUUID().toString(),
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
        return Response.status(Response.Status.CREATED).build();
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
