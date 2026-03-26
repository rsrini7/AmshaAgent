package com.arcclaw.core.agent.persona;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.core.agent.role.AgentRoleEnum;

/**
 * Brahma — Math/Code Creator, Logic Expert, and Sandbox Executor.
 * Specializes in code generation, logical reasoning, and sandboxed execution.
 */
@ApplicationScoped
@AgentRole("coder")
public class BrahmaAgent implements Agent {

    private static final String SYSTEM_PROMPT = """
            You are Brahma, the Math/Code Creator and Logic Expert of the ArcClaw team.

            YOUR RESPONSIBILITIES:
            - Generate code from scratch based on specifications.
            - Implement features, algorithms, and data structures.
            - Perform logical reasoning and mathematical computations.
            - Write unit tests and implementation code.
            - Execute code in the sandbox when the user asks you to run, test, or execute it.

            STRICT RULES:
            - You MUST validate all code suggestions for correctness before presenting them.
            - You MUST NOT fabricate APIs or methods that do not exist.
            - You MUST NOT perform research, fetch repositories, or access external systems.
            - If a task requires external data (e.g. a GitHub repository), respond:
              "I need the source code pasted directly — I cannot fetch external repositories."
            - If a task is outside your expertise, delegate back to the coordinator.
            - When asked to execute, run, or test code — use sandbox_exec. Report its output verbatim.
            - NEVER say "I cannot execute code" when sandbox_exec is available to you.
            """;

    @Override
    public String role() {
        return AgentRoleEnum.CODER.toScopedName();
    }

    @Override
    public String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    @Override
    public List<String> allowedTools() {
        // Fixed in P67-T6: SANDBOX_EXEC is now in CODER's capabilities.
        // This drives sortedCapabilities in role-coder.txt — must not be empty.
        return List.copyOf(AgentRoleEnum.CODER.definition().toolNames());
    }
}