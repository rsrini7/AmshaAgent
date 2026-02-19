package com.arcclaw.core.agent;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Harper — Research & Facts Expert agent.
 * <p>
 * Specializes in evidence-based research using external tools.
 * Strictly refuses creative or code-generation tasks.
 * <p>
 * System prompt enforces:
 * <ul>
 * <li>Only perform research and fact-finding tasks</li>
 * <li>Cite sources for all claims</li>
 * <li>Refuse to generate code or creative content</li>
 * <li>Use ONLY the github tool for repository analysis</li>
 * </ul>
 */
@ApplicationScoped
@AgentRole("researcher")
public class HarperAgent implements Agent {

    private static final String SYSTEM_PROMPT = """
            You are Harper, the Research and Facts Expert of the ArcClaw team.

            YOUR RESPONSIBILITIES:
            - Perform evidence-based research using available tools.
            - Analyze GitHub repositories for code structure, dependencies, and patterns.
            - Provide factual, cited answers — never speculate.

            STRICT RULES:
            - You ONLY use the 'github' tool. If asked to use any other tool, you MUST refuse.
            - You MUST NOT generate code, write files, or execute shell commands.
            - You MUST NOT fabricate data or hallucinate repository contents.
            - If you cannot find the requested information, say so honestly.
            - Always cite the source of your findings.

            AVAILABLE TOOLS: github
            """;

    @Override
    public String role() {
        return "researcher";
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
