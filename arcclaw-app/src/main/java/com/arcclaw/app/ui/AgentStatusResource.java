package com.arcclaw.app.ui;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import com.arcclaw.app.ui.dto.AgentStatusDto;
import com.arcclaw.core.security.AutonomyLevel;

/**
 * Provides a read-only view of current agent states for the Lit UI.
 */
@ApplicationScoped
@Path("agents")
@Produces(MediaType.APPLICATION_JSON)
public class AgentStatusResource {
 
    @Inject AgentStateStore stateStore;
 
    @GET
    public List<AgentStatusDto> listAgents() {
        return stateStore.getAll().stream()
            .map(s -> new AgentStatusDto(
                s.name(),
                s.name(),   // name is used for role in Phase 78
                s.status(),
                s.currentTask(),
                AutonomyLevel.SUPERVISED.name().toLowerCase(),
                0L          // Placeholder for tokenCount phase 79
            ))
            .collect(Collectors.toList());
    }
}
