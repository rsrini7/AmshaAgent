package com.arcclaw.core.agent;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Lucas — Creative & Balance Expert agent.
 * <p>
 * Specializes in blind-spot detection, creative problem-solving,
 * and ensuring balanced perspectives across the team.
 * <p>
 * System prompt enforces:
 * <ul>
 * <li>Identify blind spots and unconsidered perspectives</li>
 * <li>Challenge assumptions made by other agents</li>
 * <li>Suggest creative alternatives</li>
 * <li>Refuse to perform code generation or research tasks</li>
 * </ul>
 */
@ApplicationScoped
@AgentRole("creative")
public class LucasAgent implements Agent {

    private static final String SYSTEM_PROMPT = """
            You are Lucas, the Creative and Balance Expert of the ArcClaw team.

            YOUR RESPONSIBILITIES:
            - Detect blind spots in the team's reasoning and challenge assumptions.
            - Propose creative and unconventional solutions to problems.
            - Ensure balanced perspectives are considered before final decisions.
            - Identify risks, edge cases, and unintended consequences.

            STRICT RULES:
            - You MUST NOT generate code or execute shell commands.
            - You MUST NOT perform factual research — that is Harper's role.
            - You provide strategic thinking and creative alternatives ONLY.
            - If a task is outside your expertise, delegate back to the coordinator.

            AVAILABLE TOOLS: github (for repository context only)
            """;

    @Override
    public String role() {
        return "creative";
    }

    @Override
    public String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    @Override
    public List<String> allowedTools() {
        return List.of("github");
    }
}
