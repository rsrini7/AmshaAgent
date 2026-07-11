package com.amsha.core.skill;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable definition of an agent skill loaded from a YAML frontmatter markdown file.
 * <p>
 * This record standardizes the schema for skills, ensuring proper validation of bounds,
 * null safety, and immutable collections.
 * <p>
 * Example YAML Frontmatter:
 * <pre>
 * ---
 * name: code-reviewer
 * description: Reviews code for bugs and style issues
 * trigger-patterns:
 *   - "review.*code"
 *   - "check.*pr"
 * tool-capabilities:
 *   - GITHUB
 *   - RECALL_MEMORY_SEARCH
 *   - ARCHIVAL_MEMORY_SEARCH
 * max-iterations: 5
 * ---
 * You are a meticulous code reviewer...
 * </pre>
 *
 * @param id The unique identifier of the skill (auto-generated UUID if null).
 * @param name The canonical name of the skill.
 * @param description A human readable description for the /skills reflex command.
 * @param triggerPatterns List of regex strings used to auto-route user intents to this skill.
 * @param toolCapabilities List of tool capability enum strings (e.g. GITHUB, RECALL_MEMORY_SEARCH, ARCHIVAL_MEMORY_SEARCH).
 * @param maxIterations Maximum allowed execution loops before halting.
 * @param systemPrompt The actual LLM prompt instruction block.
 * @param skillHome Optional filesystem path where skill companion scripts/references are extracted.
 * @param browserPreview Whether generated browser artifacts should be auto-opened and protected from chat flooding.
 * @param templateDir Optional skill-home-relative template directory.
 */
public record SkillDefinition(
        String id,
        String name,
        String description,
        List<String> triggerPatterns,
        List<String> toolCapabilities,
        int maxIterations,
        String systemPrompt,
        String skillHome,
        boolean browserPreview,
        String templateDir) {
    /**
     * Compact constructor enforcing non-null/non-blank rules and deep immutability.
     */
    public SkillDefinition {
        Objects.requireNonNull(name, "SkillDefinition.name must not be null");
        Objects.requireNonNull(systemPrompt, "SkillDefinition.systemPrompt must not be null");
        // Guard description against null - SkillReflex calls description().isBlank()
        description = (description == null) ? "" : description;
        triggerPatterns = triggerPatterns == null ? List.of() : List.copyOf(triggerPatterns);
        toolCapabilities = toolCapabilities == null ? List.of() : List.copyOf(toolCapabilities);
        if (maxIterations <= 0) maxIterations = 5;
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
    }

    /** Backward-compatible constructor for callers that do not declare artifact behavior. */
    public SkillDefinition(
            String id,
            String name,
            String description,
            List<String> triggerPatterns,
            List<String> toolCapabilities,
            int maxIterations,
            String systemPrompt,
            String skillHome) {
        this(
                id,
                name,
                description,
                triggerPatterns,
                toolCapabilities,
                maxIterations,
                systemPrompt,
                skillHome,
                false,
                null);
    }

    public SkillDefinition(
            String id,
            String name,
            String description,
            List<String> triggerPatterns,
            List<String> toolCapabilities,
            int maxIterations,
            String systemPrompt,
            String skillHome,
            boolean browserPreview) {
        this(
                id,
                name,
                description,
                triggerPatterns,
                toolCapabilities,
                maxIterations,
                systemPrompt,
                skillHome,
                browserPreview,
                null);
    }
}
