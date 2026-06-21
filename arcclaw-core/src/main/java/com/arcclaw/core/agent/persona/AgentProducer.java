package com.arcclaw.core.agent.persona;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.AgentPersona;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.structured.StructuredOutputEngine;
import com.arcclaw.core.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;

/**
 * Producer for Agent persona beans.
 * Replaces the legacy deity subclasses (Brahma, Vishnu, Shiva, etc.) with
 * data-driven DefaultAgent instances using Qute templates.
 */
@ApplicationScoped
public class AgentProducer {
    @ConfigProperty(name = "arcclaw.agent.default-timezone", defaultValue = "Asia/Kolkata")
    String defaultTimezone;

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

    @Inject
    @Location("personas/optimizer.txt")
    Template optimizerTemplate;

    @Inject
    @Location("personas/sentinel.txt")
    Template sentinelTemplate;

    @Inject
    @Location("personas/healer.txt")
    Template healerTemplate;

    @Inject
    @Location("personas/protector.txt")
    Template protectorTemplate;

    @Inject
    @Location("personas/purifier.txt")
    Template purifierTemplate;

    @Inject
    @Location("personas/nurturer.txt")
    Template nurturerTemplate;

    @Inject
    StructuredOutputEngine structuredOutputEngine;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ToolRegistry toolRegistry;

    @Produces
    @ApplicationScoped
    @AgentRole(AgentRoleEnum.COORDINATOR_ID)
    public Agent coordinator() {
        return new DefaultAgent(
                AgentPersona.VISHNU,
                () -> coordinatorTemplate,
                defaultTimezone,
                structuredOutputEngine,
                objectMapper,
                toolRegistry);
    }

    @Produces
    @ApplicationScoped
    @AgentRole(AgentRoleEnum.CODER_ID)
    public Agent coder() {
        return new DefaultAgent(
                AgentPersona.BRAHMA,
                () -> coderTemplate,
                defaultTimezone,
                structuredOutputEngine,
                objectMapper,
                toolRegistry);
    }

    @Produces
    @ApplicationScoped
    @AgentRole(AgentRoleEnum.DEBUGGER_ID)
    public Agent debugger() {
        return new DefaultAgent(
                AgentPersona.SHIVA,
                () -> debuggerTemplate,
                defaultTimezone,
                structuredOutputEngine,
                objectMapper,
                toolRegistry);
    }

    @Produces
    @ApplicationScoped
    @AgentRole(AgentRoleEnum.RESEARCHER_ID)
    public Agent researcher() {
        return new DefaultAgent(
                AgentPersona.SARASWATI,
                () -> researcherTemplate,
                defaultTimezone,
                structuredOutputEngine,
                objectMapper,
                toolRegistry);
    }

    @Produces
    @ApplicationScoped
    @AgentRole(AgentRoleEnum.ARCHITECT_ID)
    public Agent architect() {
        return new DefaultAgent(
                AgentPersona.BRIHASPATI,
                () -> architectTemplate,
                defaultTimezone,
                structuredOutputEngine,
                objectMapper,
                toolRegistry);
    }

    @Produces
    @ApplicationScoped
    @AgentRole(AgentRoleEnum.SYNTHESIZER_ID)
    public Agent synthesizer() {
        return new DefaultAgent(
                AgentPersona.DATTATREYA,
                () -> synthesizerTemplate,
                defaultTimezone,
                structuredOutputEngine,
                objectMapper,
                toolRegistry);
    }

    @Produces
    @ApplicationScoped
    @AgentRole(AgentRoleEnum.OPTIMIZER_ID)
    public Agent optimizer() {
        return new DefaultAgent(
                AgentPersona.GANESHA,
                () -> optimizerTemplate,
                defaultTimezone,
                structuredOutputEngine,
                objectMapper,
                toolRegistry);
    }

    @Produces
    @ApplicationScoped
    @AgentRole(AgentRoleEnum.SENTINEL_ID)
    public Agent sentinel() {
        return new DefaultAgent(
                AgentPersona.KARTIKEYA,
                () -> sentinelTemplate,
                defaultTimezone,
                structuredOutputEngine,
                objectMapper,
                toolRegistry);
    }

    @Produces
    @ApplicationScoped
    @AgentRole(AgentRoleEnum.HEALER_ID)
    public Agent healer() {
        return new DefaultAgent(
                AgentPersona.LAKSHMI,
                () -> healerTemplate,
                defaultTimezone,
                structuredOutputEngine,
                objectMapper,
                toolRegistry);
    }

    @Produces
    @ApplicationScoped
    @AgentRole(AgentRoleEnum.PROTECTOR_ID)
    public Agent protector() {
        return new DefaultAgent(
                AgentPersona.DURGA,
                () -> protectorTemplate,
                defaultTimezone,
                structuredOutputEngine,
                objectMapper,
                toolRegistry);
    }

    @Produces
    @ApplicationScoped
    @AgentRole(AgentRoleEnum.PURIFIER_ID)
    public Agent purifier() {
        return new DefaultAgent(
                AgentPersona.KALI,
                () -> purifierTemplate,
                defaultTimezone,
                structuredOutputEngine,
                objectMapper,
                toolRegistry);
    }

    @Produces
    @ApplicationScoped
    @AgentRole(AgentRoleEnum.NURTURER_ID)
    public Agent nurturer() {
        return new DefaultAgent(
                AgentPersona.PARVATI,
                () -> nurturerTemplate,
                defaultTimezone,
                structuredOutputEngine,
                objectMapper,
                toolRegistry);
    }
}
