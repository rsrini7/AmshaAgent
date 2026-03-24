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

import com.arcclaw.core.skill.AgentSkillEntity;
import com.arcclaw.core.skill.AgentSkillRepository;
import com.arcclaw.core.skill.SkillDefinitionParser;
import com.arcclaw.core.skill.SkillLoader;
import com.arcclaw.core.skill.SkillRegistry;
import io.smallrye.common.annotation.RunOnVirtualThread;

@ApplicationScoped
@Path("/skills")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class SkillResource {

    @Inject
    SkillRegistry registry;

    @Inject
    AgentSkillRepository repository;

    @Inject
    SkillLoader skillLoader;

    @Inject
    SkillDefinitionParser parser;

    @GET
    public List<SkillDto> listSkills() {
        return registry.getAll().stream()
            .map(e -> new SkillDto(
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
    public Response upsertSkill(String rawYamlDoc) {
        try {
            // Pre-validate that it parses correctly before saving
            var def = parser.parse(rawYamlDoc);

            var existingOptional = repository.findByName(def.name());
            
            AgentSkillEntity entity;
            if (existingOptional.isPresent()) {
                entity = existingOptional.get();
                entity.setDefinitionYaml(rawYamlDoc);
                entity.setUpdatedAt(Instant.now());
            } else {
                entity = new AgentSkillEntity(
                    def.id(),
                    def.name(),
                    rawYamlDoc,
                    Instant.now(),
                    Instant.now()
                );
            }

            repository.saveOrUpdate(entity);
            
            // Reload the registry to make it active immediately
            skillLoader.reloadAll(false);

            return Response.status(Response.Status.CREATED)
                           .entity(new MessageResponse("Skill saved and registry reloaded: " + def.name()))
                           .build();

        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                           .entity(new MessageResponse("Invalid skill definition: " + e.getMessage()))
                           .build();
        }
    }

    @DELETE
    @Path("/{name}")
    @Transactional
    public Response deleteSkill(@PathParam("name") String name) {
        var existing = repository.findByName(name);
        if (existing.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                           .entity(new MessageResponse("Database skill not found: " + name))
                           .build();
        }

        repository.deleteSkillById(existing.get().getId());
        skillLoader.reloadAll(false);

        return Response.ok(new MessageResponse("Deleted and reloaded: " + name)).build();
    }

    @GET
    @Path("/refresh")
    public Response refresh() {
        skillLoader.reloadAll(true);
        return Response.ok(new MessageResponse("Skill registry refreshed")).build();
    }

    // --- DTOs ---

    public record SkillDto(
        String id,
        String name,
        String description,
        String source,
        List<String> triggerPatterns
    ) {}

    public record MessageResponse(String message) {}
}
