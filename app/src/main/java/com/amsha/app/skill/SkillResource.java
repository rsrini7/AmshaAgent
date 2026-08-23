package com.amsha.app.skill;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.skill.SkillRegistry;
import com.amsha.persistence.api.SkillDto;
import com.amsha.persistence.api.SkillStorePort;
import com.amsha.skills.SkillDefinitionParser;
import com.amsha.skills.SkillLoader;

import org.eclipse.microprofile.openapi.annotations.Operation;

import io.smallrye.common.annotation.RunOnVirtualThread;

@Path("/skills")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class SkillResource {

    @Inject
    SkillRegistry registry;

    @Inject
    SkillStorePort skillStore;

    @Inject
    SkillLoader skillLoader;

    @Inject
    SkillDefinitionParser parser;

    @Inject
    AuditService auditService;

    @Context
    SecurityContext securityContext;

    @GET
    @Operation(summary = "List all active agent skills")
    public List<SkillResponse> listSkills() {
        return registry.getAll().stream()
                .map(e -> new SkillResponse(
                        e.definition().id(),
                        e.definition().name(),
                        e.definition().description(),
                        e.source(),
                        e.definition().triggerPatterns()))
                .collect(Collectors.toList());
    }

    @GET
    @Path("/admission")
    @Operation(summary = "List active and quarantined skill admission results")
    public SkillAdmissionResponse admissionStatus() {
        List<SkillResponse> active = listSkills();
        List<QuarantinedSkillResponse> quarantined = skillLoader.quarantinedSkills().stream()
                .map(skill -> new QuarantinedSkillResponse(skill.name(), skill.source(), skill.reasons()))
                .toList();
        return new SkillAdmissionResponse(active, quarantined);
    }

    @POST
    @Consumes(MediaType.TEXT_PLAIN)
    @Operation(summary = "Create or update a skill from YAML definition")
    public Response upsertSkill(String rawYamlDoc) {
        if (!isInternal()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new AmshaErrorResponse(
                            "Internal authorization required", "upsert requires an internal API key"))
                    .build();
        }
        String userId = resolveUserId();
        try {
            // Pre-validate that it parses correctly before saving
            var def = parser.parse(rawYamlDoc);

            var existingOptional = skillStore.findByName(def.name());

            if (existingOptional.isPresent()) {
                var existing = existingOptional.get();
                skillStore.save(
                        new SkillDto(existing.id(), existing.name(), rawYamlDoc, existing.createdAt(), Instant.now()));
            } else {
                skillStore.save(new SkillDto(def.id(), def.name(), rawYamlDoc, Instant.now(), Instant.now()));
            }

            // Reload the registry to make it active immediately
            skillLoader.reloadAll(false);

            auditService.recordMandatory(
                    "skills",
                    userId,
                    "admin",
                    SecurityEventType.SKILL_CREATED,
                    "skill=" + def.name() + " op=" + (existingOptional.isPresent() ? "update" : "create"));

            return Response.status(Response.Status.CREATED)
                    .entity(new MessageResponse("Skill saved and registry reloaded: " + def.name()))
                    .build();

        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new AmshaErrorResponse("Invalid skill definition", e.getMessage()))
                    .build();
        }
    }

    @DELETE
    @Path("/{name}")
    @Operation(summary = "Delete a skill and reload registry")
    public Response deleteSkill(@PathParam("name") String name) {
        if (!isInternal()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new AmshaErrorResponse(
                            "Internal authorization required", "delete requires an internal API key"))
                    .build();
        }
        String userId = resolveUserId();
        var existing = skillStore.findByName(name);
        if (existing.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new AmshaErrorResponse("Database skill not found", "name: " + name))
                    .build();
        }

        if (!skillStore.deleteById(existing.get().id())) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new AmshaErrorResponse(
                            "Skill was already deleted", "id: " + existing.get().id()))
                    .build();
        }
        skillLoader.reloadAll(false);

        auditService.recordMandatory("skills", userId, "admin", SecurityEventType.SKILL_DELETED, "skill=" + name);

        return Response.ok(new MessageResponse("Deleted and reloaded: " + name)).build();
    }

    @GET
    @Path("/refresh")
    @Operation(summary = "Force reload of all skills from filesystem and database")
    public Response refresh() {
        if (!isInternal()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new AmshaErrorResponse(
                            "Internal authorization required", "refresh requires an internal API key"))
                    .build();
        }
        skillLoader.reloadAll(true);
        return Response.ok(new MessageResponse("Skill registry refreshed")).build();
    }

    private boolean isInternal() {
        return securityContext != null && securityContext.isUserInRole("internal");
    }

    private String resolveUserId() {
        if (securityContext != null && securityContext.getUserPrincipal() != null) {
            return securityContext.getUserPrincipal().getName();
        }
        return WorkspaceContext.getUserIdOrUnknown();
    }

    public record SkillResponse(
            String id, String name, String description, String source, List<String> triggerPatterns) {}

    public record MessageResponse(String message) {}

    public record QuarantinedSkillResponse(String name, String source, List<String> reasons) {}

    public record SkillAdmissionResponse(List<SkillResponse> active, List<QuarantinedSkillResponse> quarantined) {}
}
