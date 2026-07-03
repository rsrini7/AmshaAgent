package com.amsha.core.skill;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Manages adaptive skill modifications.
 * <p>
 * When enabled, the LLM can modify SKILL.md and scripts in the skill directory
 * to adapt them to the current environment (OS, available tools, container status).
 * <p>
 * Adapted files are cached in a `.adapted` marker file to avoid re-adaptation.
 */
@ApplicationScoped
public class SkillAdaptationManager {

    private static final Logger LOG = Logger.getLogger(SkillAdaptationManager.class);
    private static final String ADAPTED_MARKER = ".adapted";

    @ConfigProperty(name = "amsha.skills.adaptive.enabled", defaultValue = "false")
    boolean adaptiveEnabled;

    @Inject
    SkillRegistry skillRegistry;

    /**
     * Checks if adaptive mode is enabled.
     */
    public boolean isAdaptiveEnabled() {
        return adaptiveEnabled;
    }

    /**
     * Checks if a skill has already been adapted.
     */
    public boolean isAdapted(String skillHome) {
        if (skillHome == null || skillHome.isBlank()) {
            return false;
        }
        return Files.exists(Path.of(skillHome, ADAPTED_MARKER));
    }

    /**
     * Marks a skill as adapted.
     */
    public void markAdapted(String skillHome) {
        if (skillHome == null || skillHome.isBlank()) {
            return;
        }
        try {
            Path marker = Path.of(skillHome, ADAPTED_MARKER);
            Files.writeString(marker, "adapted: true\n");
            LOG.infof("[SkillAdaptation] Marked skill as adapted: %s", skillHome);
        } catch (IOException e) {
            LOG.warnf("[SkillAdaptation] Failed to mark skill as adapted: %s", e.getMessage());
        }
    }

    /**
     * Gets the adaptation instructions for the LLM.
     * This tells the LLM how to adapt the skill for the current environment.
     */
    public String getAdaptationInstructions(String skillHome) {
        if (!adaptiveEnabled) {
            return "";
        }

        if (isAdapted(skillHome)) {
            return "\n\n## Skill Adaptation Status\n"
                    + "This skill has already been adapted for the current environment.\n"
                    + "If you need to re-adapt, delete the `.adapted` marker file in the skill directory.\n";
        }

        String os = System.getProperty("os.name", "unknown").toLowerCase();
        boolean isWindows = os.contains("windows");
        boolean isMac = os.contains("mac") || os.contains("darwin");
        boolean isLinux = os.contains("linux");

        String shellExt = isWindows ? "ps1" : "sh";
        String shellCmd = isWindows ? "powershell -File" : "bash";
        String chainOp = isWindows ? ";" : "&&";

        return "\n\n## Skill Adaptation (First Run)\n"
                + "This skill has NOT been adapted for the current environment yet.\n"
                + "You MUST adapt it before executing. Here's how:\n"
                + "\n"
                + "### Step 1: Read the SKILL.md\n"
                + "Read the SKILL.md file in: " + skillHome + "\n"
                + "\n"
                + "### Step 2: Modify the SKILL.md\n"
                + "Make these changes to the SKILL.md:\n"
                + "1. Change `allowed-tools: Bash(...)` to `allowed-tools: hostexec(...)` — this enables host execution\n"
                + "2. Remove any 'Decision Tree' or 'Quick Start' sections that encourage prerequisite checks\n"
                + "3. Remove any 'delegate' instructions — you should execute directly, not delegate\n"
                + "4. Add this at the top of the content:\n"
                + "   ```\n"
                + "   EXECUTION RULES:\n"
                + "   - Use host_exec to run scripts directly\n"
                + "   - Do NOT run prerequisite commands (jps, ps, lsof) — scripts handle this\n"
                + "   - Call scripts with full path: " + skillHome + "/scripts/<script>.sh\n"
                + "   ```\n"
                + "\n"
                + "### Step 3: Write the modified SKILL.md\n"
                + "Write the modified SKILL.md back to the same path.\n"
                + "\n"
                + "### Step 4: Mark as adapted\n"
                + "Create a file named `.adapted` in: " + skillHome + "\n"
                + "Content: `adapted: true`\n"
                + "\n"
                + "### Step 5: Execute\n"
                + "After adapting, execute the skill using host_exec with the scripts in: " + skillHome + "/scripts/\n"
                + "\n"
                + "IMPORTANT: Do ALL steps above in order. The adaptation is required before execution.\n";
    }

    /**
     * Gets the list of files that can be adapted in a skill directory.
     */
    public List<Path> getAdaptableFiles(String skillHome) {
        if (skillHome == null || skillHome.isBlank()) {
            return List.of();
        }
        try {
            Path skillDir = Path.of(skillHome);
            if (!Files.isDirectory(skillDir)) {
                return List.of();
            }
            return Files.walk(skillDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".md")
                                || name.endsWith(".sh")
                                || name.endsWith(".ps1")
                                || name.endsWith(".bat")
                                || name.endsWith(".cmd")
                                || name.endsWith(".json")
                                || name.endsWith(".yaml")
                                || name.endsWith(".yml");
                    })
                    .filter(p -> !p.getFileName().toString().equals(ADAPTED_MARKER))
                    .toList();
        } catch (IOException e) {
            LOG.warnf("[SkillAdaptation] Failed to list adaptable files: %s", e.getMessage());
            return List.of();
        }
    }
}
