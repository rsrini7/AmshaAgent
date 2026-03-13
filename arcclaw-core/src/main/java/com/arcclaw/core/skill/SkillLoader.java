package com.arcclaw.core.skill;

import com.arcclaw.core.agent.role.RoleDefinition;
import com.arcclaw.core.agent.role.RoleRegistry;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.audit.TelemetryAuditService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Orchestrates the dual-tier loading of agent skills.
 * Reads static skills from the classpath (via index.txt) and dynamic
 * skills from the SQLite database. Compiles them and populates the registries.
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

    /**
     * Triggered on application startup to perform the initial dual-tier load.
     */
    void onStart(@Observes StartupEvent ev) {
        LOG.info("Initializing AgentSkills — dual-tier load starting");
        reloadAll(false);
    }

    /**
     * Performs a full reload of skills from classpath and database.
     * Overwrites the current entries in the registries atomically.
     *
     * @param isRefresh true if triggered by a manual API refresh, false for startup.
     */
    public void reloadAll(boolean isRefresh) {
        Map<String, CompiledSkillEntry> mergedSkills = new LinkedHashMap<>();

        // Tier 1: Classpath static skills
        loadClasspathSkills(mergedSkills);

        // Tier 2: Database dynamic skills
        loadDatabaseSkills(mergedSkills);

        List<CompiledSkillEntry> finalList = List.copyOf(mergedSkills.values());
        
        // Atomically swap the registry list
        skillRegistry.reload(finalList);

        // Register dynamic roles mapped from skills
        for (CompiledSkillEntry entry : finalList) {
            roleRegistry.registerDynamic(entry.definition().name(), entry.roleDefinition());
        }

        LOG.infof("AgentSkill tier-merge complete. Loaded %d skills.", finalList.size());

        SecurityEventType eventType = isRefresh ? SecurityEventType.SKILL_REFRESHED : SecurityEventType.SKILL_LOADED;
        telemetryAuditService.record("system", "system", "system", eventType, "Loaded " + finalList.size() + " skills");
    }

    private void loadClasspathSkills(Map<String, CompiledSkillEntry> mergedSkills) {
        try (InputStream indexStream = getClass().getResourceAsStream(INDEX_PATH)) {
            if (indexStream == null) {
                LOG.warnf("classpath %s missing. Skipping static tier.", INDEX_PATH);
                return;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(indexStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;

                    String resourcePath = "/skills/" + line;
                    try (InputStream skillStream = getClass().getResourceAsStream(resourcePath)) {
                        if (skillStream == null) {
                            LOG.warnf("Skill file %s listed in index.txt but not found on classpath", resourcePath);
                            continue;
                        }
                        String content = new String(skillStream.readAllBytes(), StandardCharsets.UTF_8);
                        SkillDefinition def = parser.parse(content);
                        addSafe(mergedSkills, def, "classpath");
                    } catch (IllegalArgumentException e) {
                        LOG.warnf("Malformed static skill definition %s: %s", resourcePath, e.getMessage());
                    } catch (Exception e) {
                        LOG.warnf(e, "Failed to load static skill %s", resourcePath);
                    }
                }
            }
        } catch (Exception e) {
            LOG.errorf(e, "Fatal failure reading static skill index %s", INDEX_PATH);
        }
    }

    private void loadDatabaseSkills(Map<String, CompiledSkillEntry> mergedSkills) {
        try {
            List<AgentSkillEntity> dbSkills = repository.findAllSkills();
            for (AgentSkillEntity entity : dbSkills) {
                try {
                    SkillDefinition def = parser.parse(entity.getDefinitionYaml());
                    // DB tier overrides classpath tier if names clash
                    addSafe(mergedSkills, def, "database");
                } catch (IllegalArgumentException e) {
                    LOG.warnf("Malformed database skill definition '%s': %s", entity.getName(), e.getMessage());
                } catch (Exception e) {
                    LOG.warnf(e, "Failed to load database skill '%s'", entity.getName());
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to query database skills", e);
        }
    }

    private void addSafe(Map<String, CompiledSkillEntry> map, SkillDefinition def, String source) {
        try {
            Pattern[] compiledPatterns = compilePatterns(def.triggerPatterns());
            Set<ToolCapability> compiledTools = compileTools(def.name(), def.toolCapabilities());
            
            String canonicalRoleName = "skill:" + def.name();
            RoleDefinition roleDef = new RoleDefinition(
                canonicalRoleName,
                "Skill: " + def.name(),
                def.description(),
                def.maxIterations(),
                false, // Skills do not require HITL approval by default
                compiledTools
            );

            CompiledSkillEntry entry = new CompiledSkillEntry(def, compiledPatterns, roleDef, source);
            map.put(def.name(), entry);
        } catch (PatternSyntaxException e) {
            LOG.warnf("Failed to compile regex for skill '%s': %s", def.name(), e.getMessage());
        }
    }

    private Pattern[] compilePatterns(List<String> rawPatterns) {
        return rawPatterns.stream()
            .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE))
            .toArray(Pattern[]::new);
    }

    private Set<ToolCapability> compileTools(String skillName, List<String> rawTools) {
        return rawTools.stream()
            .map(str -> {
                String normalizedInput = str.toUpperCase().replace("_", "");
                for (ToolCapability cap : ToolCapability.values()) {
                    if (cap.name().replace("_", "").equals(normalizedInput)) {
                        return cap;
                    }
                }
                LOG.warnf("Skipping unknown ToolCapability '%s' in skill '%s'", str, skillName);
                return null;
            })
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toUnmodifiableSet());
    }
}
