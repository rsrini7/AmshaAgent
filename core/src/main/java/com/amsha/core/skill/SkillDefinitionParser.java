package com.amsha.core.skill;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.amsha.core.agent.role.ToolCapability;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

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
            new ObjectMapper(new YAMLFactory()).configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * Parses a skill document in two-block format.
     *
     * @param rawText the raw skill file content
     * @return a validated SkillDefinition
     * @throws IllegalArgumentException if the document layout or YAML is malformed
     */
    public SkillDefinition parse(String rawText) {
        return parse(rawText, null);
    }

    /**
     * Parses a skill document with an optional skill home path for companion scripts.
     *
     * @param rawText   the raw skill file content
     * @param skillHome filesystem path where skill scripts/references are located (may be null)
     * @return a validated SkillDefinition
     * @throws IllegalArgumentException if the document layout or YAML is malformed
     */
    public SkillDefinition parse(String rawText, String skillHome) {
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("Skill document cannot be empty");
        }

        // Split on lines containing only "---" (optional trailing whitespace)
        // Precisely two backslashes required in Java string to produce \s* in regex
        String[] parts = rawText.split("(?m)^---\\s*$", 3);
        if (parts.length < 3) {
            throw new IllegalArgumentException(
                    "Skill document must have exactly two --- delimiters (found " + parts.length + " sections)");
        }

        String frontmatterYaml = parts[1].trim();
        String systemPromptBody = parts[2].trim();

        SkillFrontmatter fm;
        try {
            fm = YAML_MAPPER.readValue(frontmatterYaml, SkillFrontmatter.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse skill frontmatter YAML: " + e.getMessage(), e);
        }

        if (fm == null) {
            throw new IllegalArgumentException("Parsed frontmatter is null (empty YAML)");
        }

        List<String> capabilities = new ArrayList<>();
        if (fm.toolCapabilities() != null) {
            capabilities.addAll(fm.toolCapabilities());
        }
        if (fm.allowedTools() != null) {
            capabilities.addAll(mapSkillsJarTools(fm.allowedTools()));
        }

        return new SkillDefinition(
                null, // Auto-generate UUID
                fm.name(),
                fm.description(),
                fm.triggerPatterns() != null ? fm.triggerPatterns() : List.of(),
                capabilities.stream().distinct().toList(),
                fm.maxIterations() > 0 ? fm.maxIterations() : 5,
                systemPromptBody,
                skillHome);
    }

    private static final Map<String, String> SKILLSJARS_TOOL_MAP = Map.ofEntries(
            Map.entry("bash", "SANDBOX_EXEC"),
            Map.entry("shell", "SANDBOX_EXEC"),
            Map.entry("cmd", "SANDBOX_EXEC"),
            Map.entry("browser_navigate", "BROWSER_NAVIGATE"),
            Map.entry("github", "GITHUB"),
            Map.entry("web_search", "WEB_SEARCH_BRAVE"),
            Map.entry("garuda", "DELEGATE_TO_AGENT"),
            Map.entry("delegate", "DELEGATE_TO_AGENT"),
            Map.entry("hostexec", "HOST_EXEC"),
            Map.entry("host", "HOST_EXEC"));

    private static final Pattern SKILLSJARS_TOOL_PATTERN = Pattern.compile("^(\\w+)\\(.*\\)$");

    private List<String> mapSkillsJarTools(String allowedTools) {
        if (allowedTools == null || allowedTools.isBlank()) {
            return List.of();
        }
        return Arrays.stream(allowedTools.split("\\s+"))
                .map(tool -> {
                    var cap = ToolCapability.fromToolName(tool);
                    if (cap.isPresent()) {
                        return cap.get().name();
                    }
                    // Strip SkillsJars pattern wrapper: Bash(browser-use:*) → bash
                    String lookup = tool.toLowerCase(Locale.ROOT);
                    Matcher m = SKILLSJARS_TOOL_PATTERN.matcher(lookup);
                    if (m.matches()) {
                        lookup = m.group(1);
                    }
                    return SKILLSJARS_TOOL_MAP.get(lookup);
                })
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }
}
