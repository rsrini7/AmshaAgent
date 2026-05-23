package com.arcclaw.app.skill;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.arcclaw.core.api.dto.ArcClawErrorResponse;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.skill.SkillDefinitionParser;
import com.arcclaw.core.skill.SkillLoader;
import com.arcclaw.core.skill.SkillRegistry;
import com.arcclaw.persistence.api.SkillDto;
import com.arcclaw.persistence.api.SkillStorePort;
import io.smallrye.common.annotation.RunOnVirtualThread;
import org.eclipse.microprofile.openapi.annotations.Operation;

@ApplicationScoped
@Path("/skills")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
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

    @GET
    @Operation(summary = "List all active agent skills")
    public List<SkillResponse> listSkills() {
        return registry.getAll().stream()
            .map(e -> new SkillResponse(
                e.definition().id(),
                e.definition().name(),
                e.definition().description(),
                e.source(),
                e.definition().triggerPatterns()
            ))
            .collect(Collectors.toList());
    }

    @POST
    @Consumes(MediaType.TEXT_PLAIN)
    @Transactional
    @RunOnVirtualThread
    @Operation(summary = "Create or update a skill from YAML definition")
    public Response upsertSkill(String rawYamlDoc) {
        try {
            // Pre-validate that it parses correctly before saving
            var def = parser.parse(rawYamlDoc);

            var existingOptional = skillStore.findByName(def.name());

            if (existingOptional.isPresent()) {
                var existing = existingOptional.get();
                skillStore.save(new SkillDto(
                    existing.id(),
                    existing.name(),
                    rawYamlDoc,
                    existing.createdAt(),
                    Instant.now()
                ));
            } else {
                skillStore.save(new SkillDto(
                    def.id(),
                    def.name(),
                    rawYamlDoc,
                    Instant.now(),
                    Instant.now()
                ));
            }
            
            // Reload the registry to make it active immediately
            skillLoader.reloadAll(false);

            auditService.recordMandatory("skills", "admin", "system",
                    existingOptional.isPresent() ? SecurityEventType.SKILL_CREATED : SecurityEventType.SKILL_CREATED,
                    "skill=" + def.name() + " op=" + (existingOptional.isPresent() ? "update" : "create"));

            return Response.status(Response.Status.CREATED)
                           .entity(new MessageResponse("Skill saved and registry reloaded: " + def.name()))
                           .build();

        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                           .entity(new ArcClawErrorResponse("Invalid skill definition", e.getMessage()))
                           .build();
        }
    }

    @DELETE
    @Path("/{name}")
    @Transactional
    @RunOnVirtualThread
    @Operation(summary = "Delete a skill and reload registry")
    public Response deleteSkill(@PathParam("name") String name) {
        var existing = skillStore.findByName(name);
        if (existing.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                           .entity(new ArcClawErrorResponse("Database skill not found", "name: " + name))
                           .build();
        }

        if (!skillStore.deleteById(existing.get().id())) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(new ArcClawErrorResponse("Skill was already deleted", "id: " + existing.get().id()))
                .build();
        }
        skillLoader.reloadAll(false);

        auditService.recordMandatory("skills", "admin", "system",
                SecurityEventType.SKILL_DELETED,
                "skill=" + name);

        return Response.ok(new MessageResponse("Deleted and reloaded: " + name)).build();
    }

    @GET
    @Path("/refresh")
    @RunOnVirtualThread
    @Operation(summary = "Force reload of all skills from filesystem and database")
    public Response refresh() {
        skillLoader.reloadAll(true);
        return Response.ok(new MessageResponse("Skill registry refreshed")).build();
    }

    public record SkillResponse(
        String id,
        String name,
        String description,
        String source,
        List<String> triggerPatterns
    ) {}

    public record MessageResponse(String message) {}
}
