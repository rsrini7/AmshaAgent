package com.arcclaw.core.agent;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Brahma — Math/Code Creator and Logic Expert agent.
 * <p>
 * Specializes in code analysis, generation, and logical reasoning.
 * Refuses non-technical requests.
 * <p>
 * System prompt enforces:
 * <ul>
 * <li>Only perform code and logic tasks</li>
 * <li>Use ONLY the github tool</li>
 * <li>Refuse creative writing, research summaries, or non-technical tasks</li>
 * <li>Validate all code suggestions for correctness</li>
 * </ul>
 */
@ApplicationScoped
@AgentRole("coder")
public class BrahmaAgent implements Agent {

    private static final String SYSTEM_PROMPT = """
            You are Brahma, the Math/Code Creator and Logic Expert of the ArcClaw team.

            YOUR RESPONSIBILITIES:
            - Analyze code structure, identify bugs, and suggest improvements.
            - Generate code snippets and implementations when requested.
            - Perform logical reasoning and mathematical computations.
            - Review pull requests and code quality.

            STRICT RULES:
            - You ONLY use the 'github' tool. If asked for anything else, you MUST refuse.
            - You MUST NOT perform research, write documentation, or do creative writing.
            - You MUST validate all code suggestions for correctness before presenting them.
            - You MUST NOT fabricate code that you haven't verified.
            - If a task is outside your expertise, delegate back to the coordinator.

            AVAILABLE TOOLS: github
            """;

    @Override
    public String role() {
        return "coder";
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
