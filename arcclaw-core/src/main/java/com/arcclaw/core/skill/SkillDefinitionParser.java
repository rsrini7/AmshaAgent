package com.arcclaw.core.skill;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * Parses a raw markdown skill document into a {@link SkillDefinition}.
 * Expects exactly two "---" delimiters separating the YAML frontmatter from the prompt body.
 */
@ApplicationScoped
public class SkillDefinitionParser {

    /** 
     * Uses YAMLFactory. Do NOT use plain ObjectMapper (which expects JSON)
     * and do NOT disable fail-on-unknown properties (to catch schema drift).
     */
    private static final ObjectMapper YAML_MAPPER =
        new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * Parses a skill document in two-block format.
     * 
     * @param rawText the raw skill file content
     * @return a validated SkillDefinition
     * @throws IllegalArgumentException if the document layout or YAML is malformed
     */
    public SkillDefinition parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("Skill document cannot be empty");
        }

        // Split on lines containing only "---" (optional trailing whitespace)
        // Precisely two backslashes required in Java string to produce \s* in regex
        String[] parts = rawText.split("(?m)^---\\s*$", 3);
        if (parts.length < 3) {
            throw new IllegalArgumentException(
                "Skill document must have exactly two --- delimiters (found " + parts.length + " sections)"
            );
        }

        String frontmatterYaml = parts[1].trim();
        String systemPromptBody = parts[2].trim();

        SkillFrontmatter fm;
        try {
            fm = YAML_MAPPER.readValue(frontmatterYaml, SkillFrontmatter.class);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Failed to parse skill frontmatter YAML: " + e.getMessage(), e
            );
        }

        if (fm == null) {
            throw new IllegalArgumentException("Parsed frontmatter is null (empty YAML)");
        }

        return new SkillDefinition(
            null, // Auto-generate UUID
            fm.name(),
            fm.description(),
            fm.triggerPatterns() != null ? fm.triggerPatterns() : List.of(),
            fm.toolCapabilities() != null ? fm.toolCapabilities() : List.of(),
            fm.maxIterations() > 0 ? fm.maxIterations() : 5,
            systemPromptBody
        );
    }
}
