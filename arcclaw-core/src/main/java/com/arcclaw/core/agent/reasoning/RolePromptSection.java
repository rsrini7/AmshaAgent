package com.arcclaw.core.agent.reasoning;

import com.arcclaw.core.agent.loop.AgentExecutionContext;
import com.arcclaw.core.agent.role.AgentRoleEnum;

/**
 * Strategy for building role-specific sections of the system prompt.
 */
public interface RolePromptSection {

    /**
     * Returns the role identifier this section applies to (e.g., "coordinator", "researcher").
     */
    String getRoleName();

    /**
     * Builds the role-specific instruction block.
     * @param context the execution context
     * @param roleEnum the role enum
     * @param isCompact whether compact mode is active
     * @param isSimpleChitchat whether it's simple conversational intent
     * @return the prompt section text
     */
    String buildSection(
            AgentExecutionContext context, AgentRoleEnum roleEnum, boolean isCompact, boolean isSimpleChitchat);
}
