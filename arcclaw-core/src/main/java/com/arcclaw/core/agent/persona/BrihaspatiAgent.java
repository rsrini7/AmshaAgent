package com.arcclaw.core.agent.persona;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.core.agent.role.AgentRoleEnum;

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
 * <li>Use memory_search and github to access prior context/code</li>
 * </ul>
 */
@ApplicationScoped
@AgentRole("architect")
public class BrihaspatiAgent implements Agent {

    // %s is replaced at construction time with the live tool list from AgentRoleEnum.
    // This ensures AVAILABLE TOOLS always matches allowedTools() — no manual drift.
    private static final String SYSTEM_PROMPT_TEMPLATE = """
            You are Brihaspati, the Guru of the Devas and System Designer and Standards Author of the ArcClaw team.

            YOUR RESPONSIBILITIES:
            - Design system architecture and evaluate technical trade-offs.
            - Author Architectural Decision Records (ADRs).
            - Review proposed changes for architectural consistency.

            STRICT RULES:
            - You MUST NOT write production code — this is Brahma's role.
            - You MUST NOT generate shell commands or schedule tasks.
            - You analyse and guide; you do not implement.
            - If a task is outside your expertise, delegate back to the coordinator.

            AVAILABLE TOOLS: %s
            """;

    private final String systemPrompt;

    public BrihaspatiAgent() {
        String tools = String.join(", ", AgentRoleEnum.ARCHITECT.definition().toolNames());
        this.systemPrompt = SYSTEM_PROMPT_TEMPLATE.formatted(tools);
    }

    @Override
    public String role() {
        return AgentRoleEnum.ARCHITECT.toScopedName();
    }

    @Override
    public String systemPrompt() {
        return systemPrompt;
    }

    @Override
    public List<String> allowedTools() {
        return List.copyOf(AgentRoleEnum.ARCHITECT.definition().toolNames());
    }
}