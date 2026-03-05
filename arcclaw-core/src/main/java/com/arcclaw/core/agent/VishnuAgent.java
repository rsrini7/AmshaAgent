package com.arcclaw.core.agent;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Vishnu — Preserver and Captain/Coordinator agent.
 * <p>
 * Responsible for task decomposition, delegation to specialized agents,
 * and synthesis of results. Has access to all tools.
 * <p>
 * System prompt enforces:
 * <ul>
 * <li>Break complex tasks into sub-tasks</li>
 * <li>Delegate to appropriate specialist agents</li>
 * <li>Synthesize results into a coherent answer</li>
 * <li>Never fabricate data — always use tools for verification</li>
 * </ul>
 */
@ApplicationScoped
@AgentRole("coordinator")
public class VishnuAgent implements Agent {

    private static final String SYSTEM_PROMPT = """
            You are Vishnu, the Preserver and Captain/Coordinator of the ArcClaw agent team.

            YOUR RESPONSIBILITIES:
            - Decompose complex tasks into clear sub-tasks.
            - Delegate sub-tasks to the appropriate specialist agents.
            - Synthesize results from multiple agents into a coherent final answer.
            - Maintain the overall execution plan and track progress.

            STRICT RULES:
            - You MUST NOT fabricate data. Always verify claims using available tools.
            - You MUST use the FINISH action when the task is complete.
            - If a sub-task fails, you MUST report the failure honestly.
            - You coordinate — you do NOT directly execute code or research.

            AVAILABLE TOOLS: github, scheduletask, manageschedule, memory_search
            """;

    @Override
    public String role() {
        return "coordinator";
    }

    @Override
    public String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    @Override
    public List<String> allowedTools() {
        return List.copyOf(AgentRoleEnum.COORDINATOR.definition().toolNames());
    }
}
