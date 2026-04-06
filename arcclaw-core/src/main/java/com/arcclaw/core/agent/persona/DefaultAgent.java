package com.arcclaw.core.agent.persona;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import com.arcclaw.channel.api.structured.SynthesisResult;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.structured.StructuredOutputEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import org.jboss.logging.Logger;

/**
 * Data-driven implementation of the {@link Agent} interface.
 * System prompts are rendered via Qute templates from src/main/resources/templates/personas/{role}.txt
 */
public class DefaultAgent implements Agent {

    private static final Logger log = Logger.getLogger(DefaultAgent.class);

    private final AgentRoleEnum roleEnum;
    private final Template promptTemplate;

    @Inject
    StructuredOutputEngine structuredOutputEngine;

    @Inject
    ObjectMapper objectMapper;

    public DefaultAgent(AgentRoleEnum roleEnum, Template promptTemplate) {
        this.roleEnum = roleEnum;
        this.promptTemplate = promptTemplate;
    }

    @Override
    public String role() {
        return roleEnum.toScopedName();
    }

    @Override
    public String systemPrompt() {
        TemplateInstance instance = promptTemplate.instance();
        instance.data("tools", String.join(", ", roleEnum.definition().toolNames()));
        return instance.render();
    }

    @Override
    public List<String> allowedTools() {
        return List.copyOf(roleEnum.definition().toolNames());
    }

    @Override
    public AgentResult synthesize(String sessionId, String combinedContext, List<ReasoningStep> history) {
        if (!AgentRoleEnum.SYNTHESIZER.id().equals(roleEnum.id())) {
            return Agent.super.synthesize(sessionId, combinedContext, history);
        }

        // Logic moved from DattatreyaAgent
        SynthesisResult result = structuredOutputEngine.invoke(
                sessionId, role(), combinedContext, "SynthesisFunction", SynthesisResult.class);

        log.infof("[%s] Synthesis complete: %d contributions integrated.",
                roleEnum.id(),
                result.contributions() != null ? result.contributions().size() : 0);

        try {
            String json = objectMapper.writeValueAsString(result);
            history.add(new ReasoningStep(
                "I have synthesized the specialist findings into a final resolution.",
                ReasoningStep.FINISH,
                Map.of("answer", result.finalResolution(), "structured", json),
                "Structured synthesis complete.",
                0, 0
            ));
        } catch (Exception e) {
            log.warnf("Failed to serialize synthesis result for session %s: %s",
                sessionId, e.getMessage());
        }

        return AgentResult.structuredFinished(result.finalResolution(), result, List.copyOf(history));
    }
}
