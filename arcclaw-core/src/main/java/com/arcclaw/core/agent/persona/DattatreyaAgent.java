package com.arcclaw.core.agent.persona;

import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.structured.SynthesisResult;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.structured.StructuredOutputEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

/**
 * Dattatreya — The Synthesizer and Avadhuta.
 * <p>
 * The Guru of Gurus, famously learning from 24 teachers. In ArcClaw this maps
 * to a meta-agent role that integrates outputs from specialists.
 * <p>
 * System prompt enforces:
 * <ul>
 * <li>Synthesize multi-agent findings into coherent answers</li>
 * <li>Resolve conflicts between specializations</li>
 * <li>Do not fabricate data; do not generate code from scratch</li>
 * <li>Delegate to specialists for single-domain tasks</li>
 * </ul>
 */
@ApplicationScoped
@AgentRole("synthesizer")
public class DattatreyaAgent implements Agent {

    private static final Logger log = Logger.getLogger(DattatreyaAgent.class);

    @Inject
    StructuredOutputEngine structuredOutputEngine;

    // %s is replaced at construction time with the live tool list from AgentRoleEnum.
    // This ensures AVAILABLE TOOLS always matches allowedTools() — no manual drift.
    private static final String SYSTEM_PROMPT_TEMPLATE = """
            You are Dattatreya, the Synthesizer and Avadhuta of the ArcClaw team.
            You are the trinity incarnate — the union of Brahma, Vishnu, and Shiva.

            YOUR RESPONSIBILITIES:
            - Integrate outputs from multiple specialist agents into a single
              coherent, non-contradictory final answer.
            - Resolve conflicts between code analysis (Brahma), debug findings (Shiva),
              research data (Saraswati), and architectural guidance (Brihaspati).
            - Handle tasks that simultaneously require all specializations — you are
              the only agent that can hold the full picture.
            - Learn from all available context before synthesizing a response.

            STRICT RULES:
            - You MUST NOT fabricate data. All claims must be grounded in tool results
              or conversation context.
            - You MUST explicitly acknowledge when specialist findings conflict and
              explain how you resolved the contradiction.
            - You MUST NOT generate code from scratch — delegate that to Brahma.
            - You MUST NOT perform pure research — delegate that to Saraswati.
            - You synthesize and integrate — you do NOT replace the specialists.
            - If a task only requires ONE specialization, delegate back to the
              appropriate specialist rather than handling it yourself.
            - If a task is outside your expertise, delegate back to the coordinator.

            AVAILABLE TOOLS: %s
            """;

    private final String systemPrompt;

    public DattatreyaAgent() {
        String tools = String.join(", ", AgentRoleEnum.SYNTHESIZER.definition().toolNames());
        this.systemPrompt = SYSTEM_PROMPT_TEMPLATE.formatted(tools);
    }

    @Override
    public String role() {
        return AgentRoleEnum.SYNTHESIZER.toScopedName();
    }

    @Override
    public String systemPrompt() {
        return systemPrompt;
    }

    @Override
    public List<String> allowedTools() {
        return List.copyOf(AgentRoleEnum.SYNTHESIZER.definition().toolNames());
    }

    @Inject
    ObjectMapper objectMapper;

    /**
     * Synthesizes multi-agent findings into a final resolution using structured output.
     */
    public AgentResult synthesize(String sessionId, String combinedContext, List<ReasoningStep> history) {
        SynthesisResult result = structuredOutputEngine.invoke(
                sessionId, role(), combinedContext, "SynthesisFunction", SynthesisResult.class);

        log.infof("[Dattatreya] Synthesis complete: %d contributions integrated. conflicts=%s",
                result.contributions() != null ? result.contributions().size() : 0,
                (result.conflictSummary() != null && !result.conflictSummary().isEmpty()) ? "YES" : "NONE");

        try {
            String json = objectMapper.writeValueAsString(result);
            // Add a synthetic reasoning step to the history so it is persisted
            // in the AgentCheckpoint's history (stateJson).
            history.add(new ReasoningStep(
                "I have synthesized the specialist findings into a final resolution.",
                ReasoningStep.FINISH,
                Map.of("answer", result.finalResolution(), "structured", json),
                "Structured synthesis complete.",
                0, 0 // Tokens already accounted for in invoke()? 
            ));
        } catch (Exception e) {
            log.warnf("Failed to serialize synthesis result for session %s: %s",
                sessionId, e.getMessage());
        }

        return AgentResult.structuredFinished(result.finalResolution(), result, List.copyOf(history));
    }
}
