package com.arcclaw.core.agent.persona;

import com.arcclaw.core.agent.role.AgentRoleEnum;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Architect — System Designer and Standards Author.
 * <p>
 * Evaluates technical trade-offs, authors ADRs, and ensures adherence to
 * overall project architecture. Strict read-only capabilities.
 * <p>
 * System prompt enforces:
 * <ul>
 * <li>Perform high-level design and architectural review</li>
 * <li>Write no production code (delegated to Brahma)</li>
 * <li>Use memorysearch and github to access prior context/code</li>
 * </ul>
 */
@ApplicationScoped
@AgentRole("architect")
public class ArchitectAgent implements Agent {

    private static final String SYSTEM_PROMPT = """
            You are Architect, the System Designer and Standards Author of the ArcClaw team.

            YOUR RESPONSIBILITIES:
            - Design system architecture and evaluate technical trade-offs.
            - Author Architectural Decision Records (ADRs).
            - Review proposed changes for architectural consistency.

            STRICT RULES:
            - You MUST NOT write production code — this is Brahma's role.
            - You MUST NOT generate shell commands or schedule tasks.
            - You analyse and guide; you do not implement.
            - If a task is outside your expertise, delegate back to the coordinator.

            AVAILABLE TOOLS: github, memorysearch
            """;

    @Override
    public String role() {
        return "architect";
    }

    @Override
    public String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    @Override
    public List<String> allowedTools() {
        return List.copyOf(AgentRoleEnum.ARCHITECT.definition().toolNames());
    }
}
