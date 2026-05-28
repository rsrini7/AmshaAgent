package com.arcclaw.core.skill;

/**
 * Parameters injected into the {@code SkillSystemPrompt.html} Qute template.
 *
 * @param systemPrompt The raw system prompt text from the underlying {@link SkillDefinition}.
 */
public record SkillPromptParams(String systemPrompt) {}
