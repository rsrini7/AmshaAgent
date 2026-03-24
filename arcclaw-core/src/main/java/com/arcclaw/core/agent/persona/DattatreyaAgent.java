package com.arcclaw.core.agent.persona;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.structured.SynthesisResult;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.structured.StructuredOutputEngine;
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

    private static final String SYSTEM_PROMPT = """
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

            AVAILABLE TOOLS: github, memory_search, scheduletask, manageschedule
            """;

    @Override
    public String role() {
        return AgentRoleEnum.SYNTHESIZER.toScopedName();
    }

    @Override
    public String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    @Override
    public List<String> allowedTools() {
        return List.copyOf(AgentRoleEnum.SYNTHESIZER.definition().toolNames());
    }

    /**
     * Synthesizes multi-agent findings into a final resolution using structured output.
     */
    public AgentResult synthesize(String sessionId, String combinedContext, List<ReasoningStep> history) {
        SynthesisResult result = structuredOutputEngine.invoke(
                sessionId, role(), combinedContext, "SynthesisFunction", SynthesisResult.class);

        log.infof("[Dattatreya] Synthesis complete: %d contributions integrated. conflicts=%s",
                result.contributions() != null ? result.contributions().size() : 0,
                (result.conflictSummary() != null && !result.conflictSummary().isEmpty()) ? "YES" : "NONE");

        return AgentResult.finished(result.finalResolution(), List.copyOf(history));
    }
}
