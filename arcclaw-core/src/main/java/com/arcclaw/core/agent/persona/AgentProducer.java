package com.arcclaw.core.agent.persona;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.AgentRoleEnum;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;

/**
 * Producer for Agent persona beans.
 * Replaces the legacy deity subclasses (Brahma, Vishnu, Shiva, etc.) with
 * data-driven DefaultAgent instances using Qute templates.
 */
@ApplicationScoped
public class AgentProducer {

    @Inject
    @Location("personas/coordinator.txt")
    Template coordinatorTemplate;

    @Inject
    @Location("personas/coder.txt")
    Template coderTemplate;

    @Inject
    @Location("personas/debugger.txt")
    Template debuggerTemplate;

    @Inject
    @Location("personas/researcher.txt")
    Template researcherTemplate;

    @Inject
    @Location("personas/architect.txt")
    Template architectTemplate;

    @Inject
    @Location("personas/synthesizer.txt")
    Template synthesizerTemplate;

    @Produces
    @ApplicationScoped
    @AgentRole("coordinator")
    public Agent coordinator() {
        return new DefaultAgent(AgentRoleEnum.COORDINATOR, coordinatorTemplate);
    }

    @Produces
    @ApplicationScoped
    @AgentRole("coder")
    public Agent coder() {
        return new DefaultAgent(AgentRoleEnum.CODER, coderTemplate);
    }

    @Produces
    @ApplicationScoped
    @AgentRole("debugger")
    public Agent debugger() {
        return new DefaultAgent(AgentRoleEnum.DEBUGGER, debuggerTemplate);
    }

    @Produces
    @ApplicationScoped
    @AgentRole("researcher")
    public Agent researcher() {
        return new DefaultAgent(AgentRoleEnum.RESEARCHER, researcherTemplate);
    }

    @Produces
    @ApplicationScoped
    @AgentRole("architect")
    public Agent architect() {
        return new DefaultAgent(AgentRoleEnum.ARCHITECT, architectTemplate);
    }

    @Produces
    @ApplicationScoped
    @AgentRole("synthesizer")
    public Agent synthesizer() {
        return new DefaultAgent(AgentRoleEnum.SYNTHESIZER, synthesizerTemplate);
    }
}
