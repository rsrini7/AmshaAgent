package com.amsha.core.agent.reasoning;

public final class SkillPromptInstructions {

    private static final String SKILL_EXECUTION_PRECEDENCE_PREAMBLE = """
            SYSTEM OVERRIDE FOR ALL SKILLS:
            Platform safety, approval, and tool-permission rules always apply to this skill.
            Within those rules, this skill's instructions and declared tool list take precedence
            over general delegation, persona-routing, or cross-domain handoff guidance in the
            system prompt.
            Do not delegate to another persona or call `garuda`. Skill roles must execute
            using their permitted tools and cannot delegate, even if a skill declares
            DELEGATE_TO_AGENT.
            If a required input is missing, ask for it instead of delegating.
            For tasks that require a tool, act first with the permitted tool and
            only then provide the final result. Do not narrate a plan before the
            first tool call unless the platform explicitly says to do so.

            """;

    private static final String SKILL_ROLE_OVERRIDE_TEMPLATE = """
            SKILL ROLE OVERRIDE (HIGHEST PRIORITY):
            You are a `skill:%s` role.
            - IGNORE any rule above that tells you to delegate execution to 'brahma', 'shiva', 'garuda', or another persona.
            - Do NOT call `garuda`, `delegate`, or any tool that is not listed under PERMITTED TOOLS. Delegation is forbidden for skill roles.
            - EXECUTE the task yourself using ONLY the tools listed under PERMITTED TOOLS.
            - If the task produces files or other artifacts, batch directory creation and all file writes into the same permitted tool call when feasible.
            - After the tool observation confirms success, IMMEDIATELY emit a `FINISH` action with a concise plain-text answer.
            - If the skill prompt says required input is missing, emit `FINISH` asking for that input. Do NOT call a tool and do NOT delegate.
            - Do NOT plan another step and do NOT leave the turn open.
            """;

    private SkillPromptInstructions() {}

    public static String skillExecutionPrecedencePreamble() {
        return SKILL_EXECUTION_PRECEDENCE_PREAMBLE;
    }

    public static String skillRoleOverride(String skillName) {
        return String.format(SKILL_ROLE_OVERRIDE_TEMPLATE, skillName);
    }
}
