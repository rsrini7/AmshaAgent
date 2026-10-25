package com.amsha.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amsha.core.agent.role.AgentRoleEnum;

import org.junit.jupiter.api.Test;

class PromptSectionBuilderTest {
    private final PromptSectionBuilder builder = new PromptSectionBuilder();

    @Test
    void compactEvidenceRuleIsLimitedToResearchAndSynthesisRoles() {
        assertTrue(builder.buildCompactResearchEvidenceRule(AgentRoleEnum.RESEARCHER, true)
                .contains("never invent citations"));
        assertTrue(builder.buildCompactResearchEvidenceRule(AgentRoleEnum.SYNTHESIZER, true)
                .contains("state uncertainty or source conflicts"));
        assertEquals("", builder.buildCompactResearchEvidenceRule(AgentRoleEnum.CODER, true));
        assertEquals("", builder.buildCompactResearchEvidenceRule(AgentRoleEnum.RESEARCHER, false));
    }

    @Test
    void coordinatorDeliveryCheckIsMandatoryInCompactPrompts() {
        CoordinatorPromptSection section = new CoordinatorPromptSection();

        assertTrue(builder.buildCompactDeliveryCheckRule(AgentRoleEnum.COORDINATOR, true)
                .contains("DELIVERY CHECK"));
        assertEquals("", builder.buildCompactDeliveryCheckRule(AgentRoleEnum.RESEARCHER, true));
        assertEquals("", builder.buildCompactDeliveryCheckRule(AgentRoleEnum.COORDINATOR, false));
        assertTrue(section.buildSection(null, AgentRoleEnum.COORDINATOR, false, false)
                .contains("DELIVERY AND ESCALATION CHECK"));
        assertEquals("", section.buildSection(null, AgentRoleEnum.COORDINATOR, true, false));
        assertEquals("", section.buildSection(null, AgentRoleEnum.COORDINATOR, true, true));
    }
}
