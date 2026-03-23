package com.arcclaw.core.skill;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.RoleDefinition;
import com.arcclaw.core.agent.role.RoleRegistry;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.audit.TelemetryAuditService;
import io.quarkus.runtime.StartupEvent;
import org.jboss.logging.Logger;

/**
 * Orchestrates the dual-tier loading of agent skills.
 * Reads static skills from the classpath (via index.txt) and dynamic
 * skills from the SQLite database. Compiles them and populates the registries.
 * <p>
 * Warning propagation: unknown ToolCapability names are silently excluded
 * but collected and returned from {@link #reloadAll(boolean)} so callers
 * (e.g. the skill save endpoint) can surface them in the API response.
 * <p>
 * Zombie guard: if ALL declared tool-capabilities are invalid the skill is
 * rejected entirely to prevent a role with an empty capability set being
 * registered and silently doing nothing when triggered.
 */
@ApplicationScoped
public class SkillLoader {

    private static final Logger LOG = Logger.getLogger(SkillLoader.class);
    private static final String INDEX_PATH = "/skills/index.txt";

    @Inject
    SkillDefinitionParser parser;

    @Inject
    AgentSkillRepository repository;

    @Inject
    SkillRegistry skillRegistry;

    @Inject
    RoleRegistry roleRegistry;

    @Inject
    TelemetryAuditService telemetryAuditService;

    // Startup hook
    /**
     * Triggered on application startup to perform the initial dual-tier load.
     * Warnings are logged but not surfaced — no API caller to receive them.
     */
    void onStart(@Observes StartupEvent ev) {
        LOG.info("Initializing AgentSkills — dual-tier load starting");
        List<String> warnings = reloadAll(false);
        if (!warnings.isEmpty()) {
            LOG.warnf("Startup skill load completed with %d warning(s):", warnings.size());
            warnings.forEach(w -> LOG.warnf("  %s", w));
        }
    }

    // Public API

    /**
     * Performs a full reload of skills from classpath and database.
     * Overwrites current registry entries atomically.
     *
     * @param isRefresh true if triggered by a manual API refresh, false for startup.
     * @return Collected warnings (unknown capabilities, pattern errors, etc.)
     *         to be surfaced to API callers. Never null, may be empty.
     */
    public List<String> reloadAll(boolean isRefresh) {
        Map<String, CompiledSkillEntry> mergedSkills = new LinkedHashMap<>();
        List<String> allWarnings = new ArrayList<>();

        // Tier 1: Classpath static skills
        allWarnings.addAll(loadClasspathSkills(mergedSkills));

        // Tier 2: Database dynamic skills (DB overrides classpath on name clash)
        allWarnings.addAll(loadDatabaseSkills(mergedSkills));

        List<CompiledSkillEntry> finalList = List.copyOf(mergedSkills.values());

        // Atomically swap the registry list
        skillRegistry.reload(finalList);

        // Register dynamic roles mapped from skills
        for (CompiledSkillEntry entry : finalList) {
            roleRegistry.registerDynamic(entry.definition().name(), entry.roleDefinition());
        }

        LOG.infof("AgentSkill tier-merge complete. Loaded %d skills.", finalList.size());

        SecurityEventType eventType = isRefresh
                ? SecurityEventType.SKILL_REFRESHED
                : SecurityEventType.SKILL_LOADED;
        telemetryAuditService.record("system", "system", "system", eventType,
                "Loaded " + finalList.size() + " skills");

        return List.copyOf(allWarnings);
    }

    // Tier loaders

    private List<String> loadClasspathSkills(Map<String, CompiledSkillEntry> mergedSkills) {
        List<String> warnings = new ArrayList<>();
        try (InputStream indexStream = getClass().getResourceAsStream(INDEX_PATH)) {
            if (indexStream == null) {
                LOG.warnf("classpath %s missing. Skipping static tier.", INDEX_PATH);
                return warnings;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(indexStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;

                    String resourcePath = "/skills/" + line;
                    try (InputStream skillStream = getClass().getResourceAsStream(resourcePath)) {
                        if (skillStream == null) {
                            String w = "Skill file '" + resourcePath
                                    + "' listed in index.txt but not found on classpath";
                            LOG.warnf(w);
                            warnings.add(w);
                            continue;
                        }
                        String content = new String(skillStream.readAllBytes(), StandardCharsets.UTF_8);
                        SkillDefinition def = parser.parse(content);
                        warnings.addAll(addSafe(mergedSkills, def, "classpath"));

                    } catch (IllegalArgumentException e) {
                        String w = "Malformed static skill '" + resourcePath + "': " + e.getMessage();
                        LOG.warnf(w);
                        warnings.add(w);
                    } catch (Exception e) {
                        LOG.warnf("Failed to load static skill %s: %s", resourcePath, e.getMessage());
                        LOG.debug("Full static skill load failure stack trace:", e);
                    }
                }
            }
        } catch (Exception e) {
            LOG.errorf("Fatal failure reading static skill index %s: %s", INDEX_PATH, e.getMessage());
            LOG.debug("Full static skill index failure stack trace:", e);
        }
        return warnings;
    }

    private List<String> loadDatabaseSkills(Map<String, CompiledSkillEntry> mergedSkills) {
        List<String> warnings = new ArrayList<>();
        try {
            List<AgentSkillEntity> dbSkills = repository.findAllSkills();
            for (AgentSkillEntity entity : dbSkills) {
                try {
                    SkillDefinition def = parser.parse(entity.getDefinitionYaml());
                    warnings.addAll(addSafe(mergedSkills, def, "database"));
                } catch (IllegalArgumentException e) {
                    String w = "Malformed database skill '" + entity.getName()
                            + "': " + e.getMessage();
                    LOG.warnf(w);
                    warnings.add(w);
                } catch (Exception e) {
                    LOG.warnf("Failed to load database skill '%s': %s", entity.getName(), e.getMessage());
                    LOG.debug("Full database skill load failure stack trace:", e);
                }
            }
        } catch (Exception e) {
            LOG.errorf("Failed to query database skills: %s", e.getMessage());
            LOG.debug("Full database skill query failure stack trace:", e);
        }
        return warnings;
    }

    // Compilation helpers

    /**
     * Compiles a skill definition into a {@link CompiledSkillEntry} and adds it
     * to the merge map.
     *
     * @return Warnings produced during compilation (unknown caps, etc.). Never null.
     * @throws IllegalArgumentException if the skill is rejected (zombie guard).
     * @throws PatternSyntaxException   if a trigger-pattern regex is malformed.
     */
    private List<String> addSafe(Map<String, CompiledSkillEntry> map,
                                  SkillDefinition def, String source) {
        List<String> warnings = new ArrayList<>();
        try {
            Pattern[] compiledPatterns = compilePatterns(def.triggerPatterns());
            CompileToolsResult toolsResult = compileTools(def.name(), def.toolCapabilities());
            warnings.addAll(toolsResult.warnings());

            // Zombie guard — every declared capability was invalid; reject the skill
            // rather than register an empty-capability role that silently does nothing.
            if (toolsResult.valid().isEmpty()
                    && def.toolCapabilities() != null
                    && !def.toolCapabilities().isEmpty()) {
                throw new IllegalArgumentException(
                        "All declared tool-capabilities are invalid: " + def.toolCapabilities()
                        + ". Valid values: " + validCapabilityNames()
                        + ". Skill rejected to prevent a zombie role.");
            }

            String canonicalRoleName = "skill:" + def.name();
            RoleDefinition roleDef = new RoleDefinition(
                    canonicalRoleName,
                    "Skill: " + def.name(),
                    def.description(),
                    def.maxIterations(),
                    false,   // Skills do not require HITL approval by default
                    toolsResult.valid()
            );

            map.put(def.name(), new CompiledSkillEntry(def, compiledPatterns, roleDef, source));

        } catch (PatternSyntaxException e) {
            String w = "Failed to compile trigger-pattern regex for skill '"
                    + def.name() + "': " + e.getMessage();
            LOG.warnf(w);
            warnings.add(w);
        }
        return warnings;
    }

    private Pattern[] compilePatterns(List<String> rawPatterns) {
        return rawPatterns.stream()
                .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE))
                .toArray(Pattern[]::new);
    }

    /**
     * Parses raw tool-capability strings into {@link ToolCapability} enum values.
     * Unknown strings are excluded and collected as warnings — they are never
     * silently dropped without a trace.
     * <p>
     * Matching is case-insensitive and underscore-tolerant
     * (e.g. {@code "memory_search"} and {@code "MEMORYSEARCH"} both resolve).
     */
    private CompileToolsResult compileTools(String skillName, List<String> rawTools) {
        if (rawTools == null || rawTools.isEmpty()) {
            return new CompileToolsResult(Set.of(), List.of());
        }

        List<String> warnings = new ArrayList<>();
        Set<ToolCapability> valid = rawTools.stream()
                .map(str -> {
                    String normalized = str.toUpperCase().replace("_", "");
                    for (ToolCapability cap : ToolCapability.values()) {
                        if (cap.name().replace("_", "").equals(normalized)) {
                            return cap;
                        }
                    }
                    String w = "Unknown ToolCapability '" + str + "' in skill '" + skillName
                            + "' — excluded. Valid values: " + validCapabilityNames();
                    LOG.warnf(w);
                    warnings.add(w);
                    return (ToolCapability) null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());

        return new CompileToolsResult(valid, List.copyOf(warnings));
    }

    // Internal result record
    /**
     * Carries both the valid compiled capabilities and any warnings
     * produced during parsing, so callers can propagate them up the stack.
     */
    private record CompileToolsResult(Set<ToolCapability> valid, List<String> warnings) {}

    // ──────────────────────────────────────────────────────────────
    // Utility
    // ──────────────────────────────────────────────────────────────

    private static String validCapabilityNames() {
        return Arrays.stream(ToolCapability.values())
                .map(Enum::name)
                .collect(Collectors.joining(", "));
    }
}
